/*
 * Copyright (c) 2016 Open Baton (http://www.openbaton.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.openbaton.nfvo.core.utils;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.net.util.SubnetUtils;
import org.apache.commons.validator.routines.UrlValidator;
import org.jgrapht.alg.cycle.DirectedSimpleCycles;
import org.jgrapht.alg.cycle.SzwarcfiterLauerSimpleCycles;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedPseudograph;
import org.openbaton.catalogue.api.DeployNSRBody;
import org.openbaton.catalogue.mano.common.AbstractVirtualLink;
import org.openbaton.catalogue.mano.common.DeploymentFlavour;
import org.openbaton.catalogue.mano.common.LifecycleEvent;
import org.openbaton.catalogue.mano.descriptor.InternalVirtualLink;
import org.openbaton.catalogue.mano.descriptor.NetworkServiceDescriptor;
import org.openbaton.catalogue.mano.descriptor.VNFComponent;
import org.openbaton.catalogue.mano.descriptor.VNFDConnectionPoint;
import org.openbaton.catalogue.mano.descriptor.VNFDependency;
import org.openbaton.catalogue.mano.descriptor.VirtualDeploymentUnit;
import org.openbaton.catalogue.mano.descriptor.VirtualLinkDescriptor;
import org.openbaton.catalogue.mano.descriptor.VirtualNetworkFunctionDescriptor;
import org.openbaton.catalogue.nfvo.NFVImage;
import org.openbaton.catalogue.nfvo.VNFPackage;
import org.openbaton.catalogue.nfvo.VimInstance;
import org.openbaton.catalogue.nfvo.VnfmManagerEndpoint;
import org.openbaton.exceptions.BadFormatException;
import org.openbaton.exceptions.BadRequestException;
import org.openbaton.exceptions.CyclicDependenciesException;
import org.openbaton.exceptions.MissingParameterException;
import org.openbaton.exceptions.NetworkServiceIntegrityException;
import org.openbaton.exceptions.NotFoundException;
import org.openbaton.nfvo.repositories.VNFDRepository;
import org.openbaton.nfvo.repositories.VimRepository;
import org.openbaton.nfvo.repositories.VnfPackageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

/** Created by lto on 13/05/15. */
@Service
@Scope("prototype")
@ConfigurationProperties(prefix = "nfvo.start")
@SuppressWarnings({"unsafe", "unchecked"})
public class NSDUtils {

  @Autowired private VimRepository vimRepository;
  @Autowired private VnfPackageRepository vnfPackageRepository;
  @Autowired private VimRepository vimInstanceRepository;
  @Autowired private VNFDRepository vnfdRepository;

  @Value("${nfvo.integrity.nsd.checks:in-all-vims}")
  private String inAllVims;

  @Value("${nfvo.marketplace.ip:marketplace.openbaton.org}")
  private String marketIp;

  @Value("${nfvo.marketplace.port:8082}")
  private int marketPort;

  private String ordered;

  private static final Pattern PATTERN =
      Pattern.compile("^(([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\.){3}([01]?\\d\\d?|2[0-4]\\d|25[0-5])$");

  public void setOrdered(String ordered) {
    this.ordered = ordered;
  }

  public String getOrdered() {
    return ordered;
  }

  private final Logger log = LoggerFactory.getLogger(this.getClass());

  public void checkEndpoint(
      NetworkServiceDescriptor networkServiceDescriptor, Iterable<VnfmManagerEndpoint> endpoints)
      throws NotFoundException {
    // since the check for existence of VNFDs is done prior to this method call, we can assume that at least one VNFD
    // exists
    if (networkServiceDescriptor.getVnfd().size() == 0) {
      throw new RuntimeException(
          "The NSD contains no VNFDs. This exception is not expected to be thrown at any time. If it is, you found a "
              + "bug.");
    }
    boolean found = false;
    for (VirtualNetworkFunctionDescriptor virtualNetworkFunctionDescriptor :
        networkServiceDescriptor.getVnfd()) {
      for (VnfmManagerEndpoint endpoint : endpoints) {
        log.debug(endpoint.getType() + " == " + virtualNetworkFunctionDescriptor.getEndpoint());
        if (endpoint.getType().equals(virtualNetworkFunctionDescriptor.getEndpoint())
            && endpoint.isActive()
            && endpoint.isEnabled()) {
          found = true;
          break;
        }
      }
      if (!found) {
        throw new NotFoundException(
            "VNFManager with endpoint: "
                + virtualNetworkFunctionDescriptor.getEndpoint()
                + " is not registered or not enabled or not active.");
      }
    }
  }

  /** Fetching vnfd already existing in thr DB based on the id */
  public List<String> fetchExistingVnfd(
      NetworkServiceDescriptor networkServiceDescriptor, String projectId)
      throws NotFoundException {
    Set<VirtualNetworkFunctionDescriptor> vnfd_add = new HashSet<>();
    Set<VirtualNetworkFunctionDescriptor> vnfd_remove = new HashSet<>();
    List<String> marketIds = new ArrayList<>();

    for (VirtualNetworkFunctionDescriptor vnfd : networkServiceDescriptor.getVnfd()) {
      if (vnfd.getId() != null) {
        if (!vnfd.getId().contains("/")) {
          // Looking for a vnfd with a real id
          log.debug("VNFD to fetch is: " + vnfd.getId());
          VirtualNetworkFunctionDescriptor vnfdNew = vnfdRepository.findFirstById(vnfd.getId());
          log.trace("VNFD fetched: " + vnfdNew);
          if (vnfdNew == null) {
            throw new NotFoundException(
                "Not found VNFD with ID: "
                    + vnfd.getId()
                    + ". Did you try to create a new VNFD instead of using an already existing one?"
                    + " In this case you should not have specified the VNFD's ID at all");
          }
          if (!log.isTraceEnabled()) {
            log.debug("Fetched VNFD: " + vnfdNew.getName());
          }
          vnfd_add.add(vnfdNew);
          vnfd_remove.add(vnfd);
        } else {
          String[] id_split = vnfd.getId().split("/");
          if (id_split.length >= 3) {
            log.debug("VNFD to fetch is: " + vnfd.getId());
            VirtualNetworkFunctionDescriptor vnfdNew =
                vnfdRepository.findFirstByProjectIdAndVendorAndNameAndVersion(
                    projectId, id_split[0], id_split[1], id_split[2]);
            log.trace("VNFD fetched: " + vnfdNew);
            if (vnfdNew == null) {

              int response = 404;
              // Check if package is available on the marketplace
              try {
                URL u =
                    new URL(
                        "http://"
                            + marketIp
                            + ":"
                            + marketPort
                            + "/api/v1/vnf-packages/"
                            + vnfd.getId());
                HttpURLConnection huc = (HttpURLConnection) u.openConnection();
                huc.setRequestMethod("GET");
                huc.connect();
                response = huc.getResponseCode();
                huc.disconnect();
              } catch (IOException e) {
                log.warn("Marketplace could not be reached!");
              }
              if (response == 200) {
                log.info("Package found on the marketplace. Downloading now.");
                // Download package from marketplace!!!
                marketIds.add(vnfd.getId());
              } else {
                throw new NotFoundException(
                    "Not found VNFD with ID: "
                        + vnfd.getId()
                        + ". Did you try to create a new VNFD instead of using an already "
                        + "existing one? In this case you should not have specified the VNFD's"
                        + " ID at all");
              }
            } else {
              vnfd_add.add(vnfdNew);
            }
            if (!log.isTraceEnabled()) {
              if (vnfdNew != null) {
                log.debug("Fetched VNFD: " + vnfdNew.getName());
              }
            }
            vnfd_remove.add(vnfd);
          } else {
            throw new NotFoundException("VNFD ID must be either in the format vendor/name/version");
          }
        }
      }
    }
    networkServiceDescriptor.getVnfd().removeAll(vnfd_remove);
    networkServiceDescriptor.getVnfd().addAll(vnfd_add);
    return marketIds;
  }

  public void fetchVimInstances(NetworkServiceDescriptor networkServiceDescriptor, String projectId)
      throws NotFoundException {
    /* Fetching VimInstances */
    for (VirtualNetworkFunctionDescriptor vnfd : networkServiceDescriptor.getVnfd()) {
      fetchVimInstances(vnfd, projectId);
    }
  }

  public void fetchVimInstances(VirtualNetworkFunctionDescriptor vnfd, String projectId)
      throws NotFoundException {
    Iterable<VimInstance> vimInstances = vimRepository.findByProjectId(projectId);
    if (!vimInstances.iterator().hasNext()) {
      throw new NotFoundException("No VimInstances in the Database");
    }
    for (VirtualDeploymentUnit vdu : vnfd.getVdu()) {
      if (vdu.getVimInstanceName() != null) {
        for (String name : vdu.getVimInstanceName()) {
          log.debug("vim instance name=" + name);
          boolean fetched = false;
          for (VimInstance vimInstance : vimInstances) {
            if ((vimInstance.getName() != null
                && vimInstance.getName().equals(name)) /*|| (vimInstance.getId() !=
            null && vimInstance.getId().equals(name_id))
                                */) {
              log.info("Found vimInstance: " + vimInstance.getName());
              fetched = true;
              break;
            }
          }
          if (!fetched) {
            throw new NotFoundException(
                "Not found VimInstance with name " + name + " in the catalogue");
          }
        }
      } else {
        log.info(
            "No vimInstances are defined in the vdu "
                + vdu.getName()
                + ". Remember to define during the NSR onboarding.");
      }
    }
  }

  public void fetchDependencies(NetworkServiceDescriptor networkServiceDescriptor)
      throws NotFoundException, BadFormatException, CyclicDependenciesException,
          NetworkServiceIntegrityException {
    /* Fetching dependencies */
    DirectedPseudograph<String, DefaultEdge> g = new DirectedPseudograph<>(DefaultEdge.class);

    //Add a vertex to the graph for each vnfd
    for (VirtualNetworkFunctionDescriptor vnfd : networkServiceDescriptor.getVnfd()) {
      g.addVertex(vnfd.getName());
    }

    // transform the requires attribute to VNFDependencies and add them to the networkServiceDescriptor
    createDependenciesFromRequires(networkServiceDescriptor);

    mergeMultipleDependency(networkServiceDescriptor);

    for (VNFDependency vnfDependency : networkServiceDescriptor.getVnf_dependency()) {
      log.trace("" + vnfDependency);

      if (vnfDependency.getSource() == null || vnfDependency.getTarget() == null) {
        throw new NetworkServiceIntegrityException(
            "Source name and Target name must be defined in the request json file");
      }

      VirtualNetworkFunctionDescriptor vnfSource =
          getVnfdFromNSD(vnfDependency.getSource(), networkServiceDescriptor);
      if (vnfSource == null) {
        throw new NetworkServiceIntegrityException(
            "VNFD source name"
                + vnfDependency.getSource()
                + " was not found in the NetworkServiceDescriptor");
      } else {
        vnfDependency.setSource_id(vnfSource.getId());
      }

      VirtualNetworkFunctionDescriptor vnfTarget =
          getVnfdFromNSD(vnfDependency.getTarget(), networkServiceDescriptor);
      if (vnfTarget == null) {
        throw new NetworkServiceIntegrityException(
            "VNFD target name"
                + vnfDependency.getTarget()
                + " was not found in the NetworkServiceDescriptor");
      } else {
        vnfDependency.setTarget_id(vnfTarget.getId());
      }

      // Add an edge to the graph
      g.addEdge(vnfDependency.getSource(), vnfDependency.getTarget());
    }

    // Get simple cycles
    DirectedSimpleCycles<String, DefaultEdge> dsc = new SzwarcfiterLauerSimpleCycles(g);
    List<List<String>> cycles = dsc.findSimpleCycles();
    // Set cyclicDependency param to the vnfd
    for (VirtualNetworkFunctionDescriptor vnfd : networkServiceDescriptor.getVnfd()) {
      for (List<String> cycle : cycles) {
        if (cycle.contains(vnfd.getName())) {
          vnfd.setCyclicDependency(true);
          if (ordered != null && Boolean.parseBoolean(ordered.trim())) {
            throw new CyclicDependenciesException(
                "There is a cyclic exception and ordered start is selected. This cannot work.");
          }
          break;
        }
      }
    }
  }

  /**
   * If the requires field in the VNFD is used, this method will transform the values from requires
   * to VNFDependencies.
   */
  private void createDependenciesFromRequires(NetworkServiceDescriptor networkServiceDescriptor)
      throws NotFoundException {
    for (VirtualNetworkFunctionDescriptor vnfd : networkServiceDescriptor.getVnfd()) {
      if (vnfd.getRequires() == null) {
        continue;
      }

      for (String vnfdName : vnfd.getRequires().keySet()) {
        VNFDependency dependency = new VNFDependency();
        for (VirtualNetworkFunctionDescriptor vnfd2 : networkServiceDescriptor.getVnfd()) {
          if (vnfd2.getName().equals(vnfdName)) {
            dependency.setSource(vnfd2.getName());
            dependency.setSource_id(vnfd2.getId());
          }
        }
        if (dependency.getSource() == null) {
          throw new NotFoundException(
              "VNFD source name "
                  + vnfdName
                  + " from the requires field in the VNFD "
                  + vnfd.getName()
                  + " was not found in the NSD.");
        }

        dependency.setTarget(vnfd.getName());
        dependency.setTarget_id(vnfd.getId());

        if (vnfd.getRequires().get(vnfdName).getParameters() == null
            || vnfd.getRequires().get(vnfdName).getParameters().isEmpty()) {
          continue;
        }

        dependency.setParameters(vnfd.getRequires().get(vnfdName).getParameters());
        networkServiceDescriptor.getVnf_dependency().add(dependency);
      }
    }
  }

  private VirtualNetworkFunctionDescriptor getVnfdFromNSD(
      String name, NetworkServiceDescriptor networkServiceDescriptor) {
    for (VirtualNetworkFunctionDescriptor virtualNetworkFunctionDescriptor :
        networkServiceDescriptor.getVnfd()) {
      if (virtualNetworkFunctionDescriptor.getName().equals(name)) {
        return virtualNetworkFunctionDescriptor;
      }
    }

    return null;
  }

  /**
   * * Merge two VNFDependency (A and B), where source and target are equals, in only one (C). C
   * contains the parameters of A and B.
   *
   * @param networkServiceDescriptor the {@link NetworkServiceDescriptor} containing the
   *     dependencies
   */
  private void mergeMultipleDependency(NetworkServiceDescriptor networkServiceDescriptor) {

    Set<VNFDependency> newDependencies = new HashSet<>();

    for (VNFDependency oldDependency : networkServiceDescriptor.getVnf_dependency()) {
      boolean contained = false;
      for (VNFDependency newDependency : newDependencies) {
        if (newDependency.getTarget().equals(oldDependency.getTarget())
            && newDependency.getSource().equals(oldDependency.getSource())) {
          log.debug("Old is: " + oldDependency);
          if (oldDependency.getParameters() != null) {
            newDependency.getParameters().addAll(oldDependency.getParameters());
          }
          contained = true;
        }
      }
      if (!contained) {
        VNFDependency newDependency = new VNFDependency();
        newDependency.setSource(oldDependency.getSource());
        newDependency.setTarget(oldDependency.getTarget());
        newDependency.setParameters(new HashSet<String>());
        log.debug("Old is: " + oldDependency);
        if (oldDependency.getParameters() != null) {
          newDependency.getParameters().addAll(oldDependency.getParameters());
        }
        newDependencies.add(newDependency);
      }
    }

    log.debug("New Dependencies are: ");
    for (VNFDependency dependency : newDependencies) {
      log.debug("" + dependency);
    }
    networkServiceDescriptor.setVnf_dependency(newDependencies);
  }

  public void checkIntegrity(NetworkServiceDescriptor networkServiceDescriptor)
      throws NetworkServiceIntegrityException {
    /* check names */
    Set<String> names = new HashSet<>();
    for (VirtualNetworkFunctionDescriptor virtualNetworkFunctionDescriptor :
        networkServiceDescriptor.getVnfd()) {
      names.add(virtualNetworkFunctionDescriptor.getName());
    }

    if (networkServiceDescriptor.getVnfd().size() > names.size()) {
      throw new NetworkServiceIntegrityException(
          "All VirtualNetworkFunctionDescriptors in the same NetworkServiceDescriptor must have different names");
    }

    for (VirtualNetworkFunctionDescriptor virtualNetworkFunctionDescriptor :
        networkServiceDescriptor.getVnfd()) {
      checkIntegrity(virtualNetworkFunctionDescriptor, networkServiceDescriptor.getVld());
    }

    for (VNFDependency vnfDependency : networkServiceDescriptor.getVnf_dependency()) {
      if (vnfDependency.getParameters() == null || vnfDependency.getParameters().isEmpty()) {
        throw new NetworkServiceIntegrityException(
            "Not found any parameters in one of the VNF dependencies defined");
      }
    }
  }

  public void checkIntegrity(VirtualNetworkFunctionDescriptor virtualNetworkFunctionDescriptor)
      throws NetworkServiceIntegrityException {
    checkIntegrity(virtualNetworkFunctionDescriptor, new HashSet<>());
  }

  public void checkIntegrity(
      VirtualNetworkFunctionDescriptor virtualNetworkFunctionDescriptor,
      Set<VirtualLinkDescriptor> virtualLinkDescriptors)
      throws NetworkServiceIntegrityException {

    if (virtualNetworkFunctionDescriptor.getName() == null
        || virtualNetworkFunctionDescriptor.getName().isEmpty()) {
      throw new NetworkServiceIntegrityException("The VNFD has to have a name.");
    }

    if (virtualNetworkFunctionDescriptor.getType() == null
        || virtualNetworkFunctionDescriptor.getType().isEmpty()) {
      throw new NetworkServiceIntegrityException(
          "The VNFD " + virtualNetworkFunctionDescriptor.getName() + " misses a VNFD type.");
    }

    if (virtualNetworkFunctionDescriptor.getEndpoint() == null
        || virtualNetworkFunctionDescriptor.getEndpoint().isEmpty()) {
      throw new NetworkServiceIntegrityException(
          "No endpoint found in VNFD " + virtualNetworkFunctionDescriptor.getName());
    }
    if (virtualNetworkFunctionDescriptor.getVdu() == null
        || virtualNetworkFunctionDescriptor.getVdu().size() == 0) {
      throw new NetworkServiceIntegrityException(
          "No VDU defined in VNFD " + virtualNetworkFunctionDescriptor.getName());
    }

    checkIntegrityVDU(virtualNetworkFunctionDescriptor);

    checkIntegrityVirtualLinks(virtualNetworkFunctionDescriptor, virtualLinkDescriptors);

    checkIntegrityLifecycleEvents(virtualNetworkFunctionDescriptor);

    checkIntegrityVNFPackage(virtualNetworkFunctionDescriptor);

    if (virtualNetworkFunctionDescriptor.getVdu() != null) {
      for (VirtualDeploymentUnit virtualDeploymentUnit :
          virtualNetworkFunctionDescriptor.getVdu()) {
        if (inAllVims.equals("in-all-vims")) {
          if (virtualDeploymentUnit.getVimInstanceName() != null
              && !virtualDeploymentUnit.getVimInstanceName().isEmpty()) {
            for (String vimName : virtualDeploymentUnit.getVimInstanceName()) {
              VimInstance vimInstance =
                  checkIntegrityVimInstance(
                      virtualNetworkFunctionDescriptor, virtualDeploymentUnit, vimName);

              checkIntegrityScaleInOut(virtualNetworkFunctionDescriptor, virtualDeploymentUnit);

              checkFlavourIntegrity(virtualNetworkFunctionDescriptor, vimInstance);

              checkIntegrityImages(
                  virtualNetworkFunctionDescriptor, virtualDeploymentUnit, vimInstance);
            }
          } else {
            log.warn(
                "Impossible to complete Integrity check because of missing VimInstances definition");
          }
        } else {
          log.error("" + inAllVims + " not yet implemented!");
          throw new UnsupportedOperationException("" + inAllVims + " not yet implemented!");
        }
      }
    } else {
      virtualNetworkFunctionDescriptor.setVdu(new HashSet<VirtualDeploymentUnit>());
    }
  }

  private VimInstance checkIntegrityVimInstance(
      VirtualNetworkFunctionDescriptor virtualNetworkFunctionDescriptor,
      VirtualDeploymentUnit virtualDeploymentUnit,
      String vimName)
      throws NetworkServiceIntegrityException {
    VimInstance vimInstance = null;
    for (VimInstance vi :
        vimRepository.findByProjectId(virtualNetworkFunctionDescriptor.getProjectId())) {
      if (vimName.equals(vi.getName())) {
        vimInstance = vi;
        log.debug("Got vim with auth: " + vimInstance.getAuthUrl());
        break;
      }
    }

    if (vimInstance == null) {
      throw new NetworkServiceIntegrityException(
          "Not found VIM with name "
              + vimName
              + " referenced by VNFD "
              + virtualNetworkFunctionDescriptor.getName()
              + " and VDU "
              + virtualDeploymentUnit.getName());
    }
    return vimInstance;
  }

  private void checkIntegrityVDU(VirtualNetworkFunctionDescriptor virtualNetworkFunctionDescriptor)
      throws NetworkServiceIntegrityException {
    int i = 1;
    for (VirtualDeploymentUnit vdu : virtualNetworkFunctionDescriptor.getVdu()) {
      if (vdu.getVnfc() == null || vdu.getVnfc().size() == 0) {
        log.warn("Not found any VNFC in VDU of VNFD " + virtualNetworkFunctionDescriptor.getName());
      }
      if (vdu.getName() == null || vdu.getName().isEmpty()) {
        vdu.setName(virtualNetworkFunctionDescriptor.getName() + "-" + i);
        i++;
      }
      if (vdu.getVm_image() == null || vdu.getVm_image().isEmpty()) {
        throw new NetworkServiceIntegrityException(
            "At least one VDU in the VNFD "
                + virtualNetworkFunctionDescriptor.getName()
                + " does not contain an image.");
      }
      vdu.setProjectId(virtualNetworkFunctionDescriptor.getProjectId());
    }
  }

  private void checkIntegrityVirtualLinks(
      VirtualNetworkFunctionDescriptor virtualNetworkFunctionDescriptor,
      Set<VirtualLinkDescriptor> virtualLinkDescriptors)
      throws NetworkServiceIntegrityException {

    if (virtualNetworkFunctionDescriptor.getVirtual_link() != null) {
      for (InternalVirtualLink vl : virtualNetworkFunctionDescriptor.getVirtual_link()) {
        if (vl.getName() == null || Objects.equals(vl.getName(), "")) {
          throw new NetworkServiceIntegrityException(
              "The VNFD "
                  + virtualNetworkFunctionDescriptor.getName()
                  + " has a virtual link with no name specified");
        }
      }
    } else {
      virtualNetworkFunctionDescriptor.setVirtual_link(new HashSet<>());
    }
    if (virtualLinkDescriptors.isEmpty()
        && virtualNetworkFunctionDescriptor.getVirtual_link().isEmpty()) {
      for (VirtualDeploymentUnit virtualDeploymentUnit :
          virtualNetworkFunctionDescriptor.getVdu()) {
        if (virtualDeploymentUnit.getVnfc() != null) {
          for (VNFComponent vnfComponent : virtualDeploymentUnit.getVnfc()) {
            if (vnfComponent.getConnection_point() != null) {
              HashSet<String> fixedIps = new HashSet<>();
              for (VNFDConnectionPoint vnfdConnectionPoint : vnfComponent.getConnection_point()) {
                if (vnfdConnectionPoint.getVirtual_link_reference() != null) {
                  log.warn(
                      String.format(
                          "A VNFC has a virtual link reference %s that is not in the list of InternalVirtualLinks. Be "
                              + "sure the NSD contains it",
                          vnfdConnectionPoint.getVirtual_link_reference()));
                }
                if (vnfdConnectionPoint.getFixedIp() != null
                    && !vnfdConnectionPoint.getFixedIp().equals("")) {
                  if (!fixedIps.add(vnfdConnectionPoint.getFixedIp())) {
                    throw new NetworkServiceIntegrityException(
                        String.format(
                            "Fixed ip %s contained at least twice!",
                            vnfdConnectionPoint.getFixedIp()));
                  }
                }
              }
            }
          }
        }
      }
    } else {
      for (VirtualDeploymentUnit virtualDeploymentUnit :
          virtualNetworkFunctionDescriptor.getVdu()) {
        if (virtualDeploymentUnit.getVnfc() != null) {
          for (VNFComponent vnfComponent : virtualDeploymentUnit.getVnfc()) {
            if (vnfComponent.getConnection_point() != null) {
              for (VNFDConnectionPoint vnfdConnectionPoint : vnfComponent.getConnection_point()) {
                if (vnfdConnectionPoint.getVirtual_link_reference() != null) {
                  if (!checkIntegrityCidr(virtualLinkDescriptors, vnfdConnectionPoint)) {
                    if (!checkIntegrityCidr(
                        virtualNetworkFunctionDescriptor.getVirtual_link(), vnfdConnectionPoint)) {
                      throw new NetworkServiceIntegrityException(
                          String.format(
                              "VirtualLinkReference %s not found in the virtual links defined in the vnfd",
                              vnfdConnectionPoint.getVirtual_link_reference()));
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  private boolean checkIntegrityCidr(
      Set<? extends AbstractVirtualLink> abstractVirtualLinks,
      VNFDConnectionPoint vnfdConnectionPoint)
      throws NetworkServiceIntegrityException {
    for (AbstractVirtualLink abstractVirtualLink : abstractVirtualLinks) {
      if (abstractVirtualLink.getName().equals(vnfdConnectionPoint.getVirtual_link_reference())) {
        if (abstractVirtualLink instanceof VirtualLinkDescriptor) {
          vnfdConnectionPoint.setChosenPool(
              ((VirtualLinkDescriptor) abstractVirtualLink).getPoolName());
        }
        if (vnfdConnectionPoint.getFixedIp() != null) {
          if (!PATTERN.matcher(vnfdConnectionPoint.getFixedIp()).matches()) {
            throw new NetworkServiceIntegrityException(
                String.format("Fixed ip %s is not a valid ip", vnfdConnectionPoint.getFixedIp()));
          }
          if (abstractVirtualLink.getCidr() == null || abstractVirtualLink.getCidr().equals("")) {
            throw new NetworkServiceIntegrityException(
                String.format(
                    "Fixed ip is set (%s) but not the cidr", vnfdConnectionPoint.getFixedIp()));
          }
          SubnetUtils subnetUtils;
          try {
            subnetUtils = new SubnetUtils(abstractVirtualLink.getCidr());
          } catch (IllegalArgumentException e) {
            throw new NetworkServiceIntegrityException(
                String.format(
                    "Cidr is not in the correct format (%s)", abstractVirtualLink.getCidr()));
          }
          if (!subnetUtils.getInfo().isInRange(vnfdConnectionPoint.getFixedIp())) {
            throw new NetworkServiceIntegrityException(
                String.format(
                    "Fixed ip (%s) is not in the cidr range (%s)",
                    vnfdConnectionPoint.getFixedIp(), abstractVirtualLink.getCidr()));
          }
        }
        return true;
      }
    }
    return false;
  }

  //                for (VNFComponent vnfComponent : virtualDeploymentUnit.getVnfc()) {
  //                  for (VNFDConnectionPoint connectionPoint : vnfComponent.getConnection_point()) {
  //                    if (!internalVirtualLink.contains(
  //                        connectionPoint.getVirtual_link_reference())) {
  //                      throw new NetworkServiceIntegrityException(
  //                          "Regarding the VirtualNetworkFunctionDescriptor "
  //                              + virtualNetworkFunctionDescriptor.getName()
  //                              + ": in one of the VirtualDeploymentUnit, the "
  //                              + "virtualLinkReference "
  //                              + connectionPoint.getVirtual_link_reference()
  //                              + " of a VNFComponent is not contained in the "
  //                              + "InternalVirtualLink "
  //                              + internalVirtualLink);
  //                    }
  //                  }
  //                }
  //      if (!virtualLinkDescriptors.containsAll(internalVirtualLink)) {
  //        throw new NetworkServiceIntegrityException(
  //            "Regarding the VirtualNetworkFunctionDescriptor "
  //                + virtualNetworkFunctionDescriptor.getName()
  //                + ": the InternalVirtualLinks "
  //                + internalVirtualLink
  //                + " are not contained in the VirtualLinkDescriptors "
  //                + virtualLinkDescriptors);
  //      }

  private void checkIntegrityLifecycleEvents(
      VirtualNetworkFunctionDescriptor virtualNetworkFunctionDescriptor)
      throws NetworkServiceIntegrityException {
    if (virtualNetworkFunctionDescriptor.getLifecycle_event() != null) {
      for (LifecycleEvent event : virtualNetworkFunctionDescriptor.getLifecycle_event()) {
        if (event == null) {
          throw new NetworkServiceIntegrityException(
              "LifecycleEvent in VNFD " + virtualNetworkFunctionDescriptor.getName() + " is null");
        } else if (event.getEvent() == null) {
          throw new NetworkServiceIntegrityException(
              "Event in one LifecycleEvent of VNFD "
                  + virtualNetworkFunctionDescriptor.getName()
                  + " does not exist");
        }
      }
    }
  }

  private void checkIntegrityVNFPackage(
      VirtualNetworkFunctionDescriptor virtualNetworkFunctionDescriptor) {
    if (virtualNetworkFunctionDescriptor.getVnfPackageLocation() != null) {
      UrlValidator urlValidator = new UrlValidator();
      if (urlValidator.isValid(
          virtualNetworkFunctionDescriptor.getVnfPackageLocation())) { // this is a script link
        VNFPackage vnfPackage = new VNFPackage();
        vnfPackage.setScriptsLink(virtualNetworkFunctionDescriptor.getVnfPackageLocation());
        vnfPackage.setName(virtualNetworkFunctionDescriptor.getName());
        vnfPackage.setProjectId(virtualNetworkFunctionDescriptor.getProjectId());
        vnfPackage = vnfPackageRepository.save(vnfPackage);
        virtualNetworkFunctionDescriptor.setVnfPackageLocation(vnfPackage.getId());
      }
    } else {
      log.warn("vnfPackageLocation is null. Are you sure?");
    }
  }

  private void checkIntegrityImages(
      VirtualNetworkFunctionDescriptor virtualNetworkFunctionDescriptor,
      VirtualDeploymentUnit virtualDeploymentUnit,
      VimInstance vimInstance)
      throws NetworkServiceIntegrityException {
    Set<String> imageNames = new HashSet<>();
    Set<String> imageIds = new HashSet<>();
    for (NFVImage image : vimInstance.getImages()) {
      imageNames.add(image.getName());
      imageIds.add(image.getExtId());
    }

    if (virtualDeploymentUnit.getVm_image() != null) {
      boolean found = false;
      for (String image : virtualDeploymentUnit.getVm_image()) {
        log.debug("Checking image: " + image);
        if (imageNames.contains(image) || imageIds.contains(image)) {
          found = true;
        }
        if (!found) {
          throw new NetworkServiceIntegrityException(
              "Regarding the VirtualNetworkFunctionDescriptor "
                  + virtualNetworkFunctionDescriptor.getName()
                  + ": in one of the VirtualDeploymentUnit, image"
                  + image
                  + " is not contained into the images of the vimInstance "
                  + "chosen. Please choose one from: "
                  + imageNames
                  + " or from "
                  + imageIds);
        }
      }
    }
  }

  private void checkIntegrityScaleInOut(
      VirtualNetworkFunctionDescriptor virtualNetworkFunctionDescriptor,
      VirtualDeploymentUnit virtualDeploymentUnit)
      throws NetworkServiceIntegrityException {
    if (virtualDeploymentUnit.getScale_in_out() < 1) {
      throw new NetworkServiceIntegrityException(
          "Regarding the VirtualNetworkFunctionDescriptor "
              + virtualNetworkFunctionDescriptor.getName()
              + ": in one of the VirtualDeploymentUnit, the scale_in_out"
              + " parameter ("
              + virtualDeploymentUnit.getScale_in_out()
              + ") must be at least 1");
    }
    if (virtualDeploymentUnit.getScale_in_out() < virtualDeploymentUnit.getVnfc().size()) {
      throw new NetworkServiceIntegrityException(
          "Regarding the VirtualNetworkFunctionDescriptor "
              + virtualNetworkFunctionDescriptor.getName()
              + ": in one of the VirtualDeploymentUnit, the scale_in_out"
              + " parameter ("
              + virtualDeploymentUnit.getScale_in_out()
              + ") must not be less than the number of starting "
              + "VNFComponent: "
              + virtualDeploymentUnit.getVnfc().size());
    }
  }

  private void checkFlavourIntegrity(
      VirtualNetworkFunctionDescriptor virtualNetworkFunctionDescriptor, VimInstance vimInstance)
      throws NetworkServiceIntegrityException {
    Set<String> flavourNames = new HashSet<>();
    if (virtualNetworkFunctionDescriptor.getDeployment_flavour() != null
        && !virtualNetworkFunctionDescriptor.getDeployment_flavour().isEmpty()) {
      for (DeploymentFlavour deploymentFlavour :
          virtualNetworkFunctionDescriptor.getDeployment_flavour()) {
        if (deploymentFlavour.getFlavour_key() != null
            && !deploymentFlavour.getFlavour_key().isEmpty()) {
          flavourNames.add(deploymentFlavour.getFlavour_key());
        } else {
          throw new NetworkServiceIntegrityException(
              "Deployment flavor of VNFD "
                  + virtualNetworkFunctionDescriptor.getName()
                  + " is not well defined");
        }
      }
    } else {
      for (VirtualDeploymentUnit vdu : virtualNetworkFunctionDescriptor.getVdu()) {
        if (vdu.getComputation_requirement() == null
            || vdu.getComputation_requirement().isEmpty()) {
          throw new NetworkServiceIntegrityException(
              "Flavour must be set in VNFD or all VDUs: "
                  + virtualNetworkFunctionDescriptor.getName()
                  + ". Come on... check the PoP page and pick at least one "
                  + "DeploymentFlavor");
        } else {
          flavourNames.add(vdu.getComputation_requirement());
        }
      }
    }

    Set<String> flavors = new HashSet<>();
    if (vimInstance.getFlavours() == null) {
      throw new NetworkServiceIntegrityException(
          "No flavours found on your VIM instance, therefore it is not possible to on board your NSD");
    }
    for (DeploymentFlavour deploymentFlavour : vimInstance.getFlavours()) {
      flavors.add(deploymentFlavour.getFlavour_key());
    }
    //All "names" must be contained in the "flavors"
    if (!flavors.containsAll(flavourNames)) {
      throw new NetworkServiceIntegrityException(
          "Regarding the VirtualNetworkFunctionDescriptor "
              + virtualNetworkFunctionDescriptor.getName()
              + ": in one of the VirtualDeploymentUnit, not all "
              + "DeploymentFlavour"
              + flavourNames
              + " are contained into the flavors of the vimInstance "
              + "chosen. Please choose one from: "
              + flavors);
    }
  }

  public List<String> getRuntimeDeploymentInfo(DeployNSRBody body, VirtualDeploymentUnit vdu)
      throws MissingParameterException {
    List<String> instanceNames;

    if (body == null
        || body.getVduVimInstances() == null
        || body.getVduVimInstances().get(vdu.getName()) == null
        || body.getVduVimInstances().get(vdu.getName()).isEmpty()) {
      if (vdu.getVimInstanceName() == null) {
        throw new MissingParameterException(
            "No VimInstance specified for vdu with name: " + vdu.getName());
      }
      instanceNames = vdu.getVimInstanceName();
    } else {
      instanceNames = body.getVduVimInstances().get(vdu.getName());
    }
    return instanceNames;
  }

  public List<String> checkIfVimAreSupportedByPackage(
      VirtualNetworkFunctionDescriptor vnfd, List<String> instanceNames)
      throws BadRequestException {
    VNFPackage vnfPackage = vnfPackageRepository.findFirstById(vnfd.getVnfPackageLocation());
    if (vnfPackage == null
        || vnfPackage.getVimTypes() == null
        || vnfPackage.getVimTypes().size() == 0) {
      log.warn("VNFPackage does not provide supported VIM. I will skip the check!");
    } else {
      for (String vimInstanceName : instanceNames) {
        VimInstance vimInstance;
        for (VimInstance vi : vimInstanceRepository.findByProjectId(vnfd.getProjectId())) {
          if (vimInstanceName.equals(vi.getName())) {
            vimInstance = vi;
            log.debug("Found vim instance " + vimInstance.getName());
            log.debug(
                "Checking if "
                    + vimInstance.getType()
                    + " is contained in "
                    + vnfPackage.getVimTypes());
            if (!vnfPackage.getVimTypes().contains(vimInstance.getType())) {
              throw new org.openbaton.exceptions.BadRequestException(
                  "The Vim Instance chosen does not support the VNFD " + vnfd.getName());
            }
          }
        }
      }
    }
    if (instanceNames.size() == 0) {
      for (VimInstance vimInstance : vimInstanceRepository.findByProjectId(vnfd.getProjectId())) {
        if (vnfPackage == null
            || vnfPackage.getVimTypes() == null
            || vnfPackage.getVimTypes().isEmpty()) {
          instanceNames.add(vimInstance.getName());
        } else {
          String type = vimInstance.getType();
          if (type.contains(".")) {
            type = type.split("\\.")[0];
          }
          if (vnfPackage.getVimTypes().contains(type)) {
            instanceNames.add(vimInstance.getName());
          }
        }
      }
    }

    if (instanceNames.size() == 0) {
      throw new org.openbaton.exceptions.BadRequestException(
          "No Vim Instance found for supporting the VNFD "
              + vnfd.getName()
              + " (looking for vim type: "
              + vnfPackage.getVimTypes()
              + ")");
    }
    log.debug("Vim Instances chosen are: " + instanceNames);
    return instanceNames;
  }
}
