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

package org.openbaton.tosca.parser;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.commons.validator.routines.UrlValidator;
import org.openbaton.catalogue.mano.descriptor.NetworkServiceDescriptor;
import org.openbaton.catalogue.mano.descriptor.VirtualNetworkFunctionDescriptor;
import org.openbaton.catalogue.nfvo.Script;
import org.openbaton.catalogue.nfvo.VNFPackage;
import org.openbaton.catalogue.nfvo.images.BaseNfvImage;
import org.openbaton.catalogue.nfvo.images.NFVImage;
import org.openbaton.exceptions.AlreadyExistingException;
import org.openbaton.exceptions.BadFormatException;
import org.openbaton.exceptions.BadRequestException;
import org.openbaton.exceptions.EntityUnreachableException;
import org.openbaton.exceptions.IncompatibleVNFPackage;
import org.openbaton.exceptions.NotFoundException;
import org.openbaton.exceptions.PluginException;
import org.openbaton.exceptions.VimException;
import org.openbaton.nfvo.core.interfaces.VNFPackageManagement;
import org.openbaton.nfvo.repositories.VNFDRepository;
import org.openbaton.nfvo.repositories.VnfPackageRepository;
import org.openbaton.tosca.templates.NSDTemplate;
import org.openbaton.tosca.templates.VNFDTemplate;
import org.openbaton.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/** Created by rvl on 12.09.16. */
@Service
@SuppressWarnings({"unsafe", "unchecked"})
public class CSARParser {

  @Autowired private VNFDRepository vnfdRepository;
  @Autowired private VNFPackageManagement vnfPackageManagement;
  @Autowired private VnfPackageRepository vnfPackageRepository;

  private final Logger log = LoggerFactory.getLogger(this.getClass());

  private TOSCAParser toscaParser;

  private Set<Script> scripts = new HashSet<>();
  private ByteArrayOutputStream metadata;
  private ByteArrayOutputStream template;
  private ByteArrayOutputStream vnfMetadata;
  private ArrayList<String> folderNames = new ArrayList<>();

  public CSARParser() {
    this.toscaParser = new TOSCAParser();
  }

  private void readFiles(InputStream csar_file) throws IOException, NotFoundException {

    ZipInputStream zipStream = new ZipInputStream(csar_file);
    ZipEntry entry;
    this.scripts.clear();
    this.folderNames.clear();
    this.template = new ByteArrayOutputStream();
    this.metadata = new ByteArrayOutputStream();

    while ((entry = zipStream.getNextEntry()) != null) {

      if (!entry.isDirectory()) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        int count;
        byte[] buffer = new byte[1024];
        while ((count = zipStream.read(buffer)) != -1) {
          baos.write(buffer, 0, count);
        }

        String fileName = entry.getName();

        if (fileName.toLowerCase().endsWith(".meta")) {
          this.metadata = baos;
        } else if (fileName.toLowerCase().endsWith(".yaml")) {
          if (fileName.toLowerCase().endsWith("metadata.yaml")) {
            this.vnfMetadata = baos;
          } else {
            this.template = baos;
          }
        } else {

          Script script = new Script();
          String[] splittedName = fileName.split("/");
          if (splittedName.length > 2) {
            String scriptName = splittedName[1] + "!_!" + splittedName[splittedName.length - 1];
            folderNames.add(splittedName[1]);
            script.setName(scriptName);

          } else script.setName(splittedName[splittedName.length - 1]);

          script.setPayload(baos.toByteArray());
          this.scripts.add(script);
        }
      }
    }
    if (this.metadata == null) {
      throw new NotFoundException("CSARParser: You have to include the TOSCA.meta");
    }
    if (this.vnfMetadata == null) {
      throw new NotFoundException("CSARParser: You have to include the Metadata.yaml");
    }
    if (this.template == null) {
      throw new NotFoundException("CSARParser: No NSD or VNFD Template found");
    }

    //zipStream.close();
  }

  private void readMetaData() throws IOException {

    BufferedReader br =
        new BufferedReader(
            new InputStreamReader(new ByteArrayInputStream(this.metadata.toByteArray())));
    String strLine;

    String entryDefinition = "Entry-Definitions:";

    while ((strLine = br.readLine()) != null) {

      if (strLine.contains(entryDefinition)) {
        strLine.substring(entryDefinition.length(), strLine.length());
      }
    }

    br.close();
  }

  //TODO what is the need of such method? Only for testing purposes?
  public void parseVNFCSAR(String vnfd_csar) throws Exception {

    InputStream csar = new FileInputStream(vnfd_csar);
    readFiles(csar);

    readMetaData();

    VNFDTemplate VNFDTemplate = Utils.bytesToVNFDTemplate(this.template);
    toscaParser.parseVNFDTemplate(VNFDTemplate);
  }

  public NetworkServiceDescriptor parseNSDCSAR(String nsd_csar) throws Exception {

    InputStream input = new FileInputStream(new File(nsd_csar));
    readFiles(input);

    readMetaData();
    NSDTemplate nsdTemplate = Utils.bytesToNSDTemplate(this.template);
    return toscaParser.parseNSDTemplate(nsdTemplate);
  }

  private BaseNfvImage getImage(
      VNFPackage vnfPackage,
      VirtualNetworkFunctionDescriptor virtualNetworkFunctionDescriptor,
      String projectId)
      throws NotFoundException, PluginException, VimException, IncompatibleVNFPackage,
          BadRequestException, IOException, AlreadyExistingException, BadFormatException,
          InterruptedException, EntityUnreachableException {

    Map<String, Object> metadata;
    NFVImage image = new NFVImage();
    Map<String, Object> imageDetails = new HashMap<>();
    byte[] imageFile = null;

    Yaml yaml = new Yaml();
    metadata = yaml.loadAs(new String(this.vnfMetadata.toByteArray()), Map.class);
    //Get configuration for NFVImage
    imageDetails = vnfPackageManagement.handleMetadata(metadata, vnfPackage, imageDetails, image);

    vnfPackageManagement.handleImage(
        vnfPackage,
        imageFile,
        virtualNetworkFunctionDescriptor,
        metadata,
        image,
        imageDetails,
        projectId);

    return image;
  }

  private String saveVNFD(
      VirtualNetworkFunctionDescriptor vnfd,
      String projectId,
      Set<Script> vnfScripts,
      String scriptsLink)
      throws PluginException, VimException, NotFoundException, IncompatibleVNFPackage,
          BadRequestException, IOException, AlreadyExistingException, InterruptedException,
          EntityUnreachableException, BadFormatException {

    VNFPackage vnfPackage = new VNFPackage();

    vnfPackage.setImage(getImage(vnfPackage, vnfd, projectId));
    if (vnfScripts != null) {
      vnfPackage.setScripts(vnfScripts);
    } else if (scriptsLink != null) vnfPackage.setScriptsLink(scriptsLink);
    vnfPackage.setName(vnfd.getName());
    vnfPackage.setProjectId(projectId);

    VNFPackage savedPackage = vnfPackageRepository.save(vnfPackage);

    vnfd.setVnfPackageLocation(savedPackage.getId());
    SimpleDateFormat format = new SimpleDateFormat("yyyy.MM.dd 'at' HH:mm:ss z");
    vnfd.setCreatedAt(format.format(new Date()));
    vnfd.setUpdatedAt(format.format(new Date()));
    vnfd.setProjectId(projectId);
    vnfdRepository.save(vnfd);

    return savedPackage.getId();
  }

  /*
   *
   * MAIN FUNCTIONS
   *
   */

  public VirtualNetworkFunctionDescriptor onboardVNFD(byte[] bytes, String projectId)
      throws NotFoundException, PluginException, VimException, IOException, IncompatibleVNFPackage,
          org.openbaton.tosca.exceptions.NotFoundException, BadRequestException,
          AlreadyExistingException, BadFormatException, InterruptedException,
          EntityUnreachableException {

    InputStream input = new ByteArrayInputStream(bytes);

    readFiles(input);

    VNFDTemplate vnfdt = Utils.bytesToVNFDTemplate(this.template);
    VirtualNetworkFunctionDescriptor vnfd = toscaParser.parseVNFDTemplate(vnfdt);

    String scriptsLink = null;
    UrlValidator urlValidator = new UrlValidator();
    if (urlValidator.isValid(vnfd.getVnfPackageLocation())) {
      scriptsLink = vnfd.getVnfPackageLocation();
    }
    saveVNFD(vnfd, projectId, scripts, scriptsLink);

    input.close();
    this.template.close();
    this.metadata.close();

    return vnfd;
  }

  public NetworkServiceDescriptor onboardNSD(byte[] bytes, String projectId)
      throws NotFoundException, PluginException, VimException, IOException, IncompatibleVNFPackage,
          org.openbaton.tosca.exceptions.NotFoundException, BadRequestException,
          AlreadyExistingException, BadFormatException, InterruptedException,
          EntityUnreachableException {

    InputStream input = new ByteArrayInputStream(bytes);
    ArrayList<String> ids = new ArrayList<>();

    readFiles(input);

    NSDTemplate nsdTemplate = Utils.bytesToNSDTemplate(this.template);
    NetworkServiceDescriptor nsd = toscaParser.parseNSDTemplate(nsdTemplate);

    for (VirtualNetworkFunctionDescriptor vnfd : nsd.getVnfd()) {
      Set<Script> vnfScripts = null;
      String link = null;
      if (!folderNames.contains(vnfd.getType())) {
        UrlValidator urlValidator = new UrlValidator();
        if (urlValidator.isValid(vnfd.getVnfPackageLocation())) {
          link = vnfd.getVnfPackageLocation();
        } else
          log.warn(
              "No Scripts specified for the VNFD of type: "
                  + vnfd.getType()
                  + " and scripts link not valid");
      } else {
        vnfScripts = new HashSet<>();
        for (Script script : scripts) {
          String[] splitted_name = script.getName().split("!_!");
          log.debug(splitted_name[0]);
          log.debug(script.getName());

          if (splitted_name.length == 2) {
            String folder_name = splitted_name[0];
            if (folder_name.equals(vnfd.getType())) {
              Script s = new Script();
              s.setName(splitted_name[1]);
              s.setPayload(script.getPayload());
              vnfScripts.add(s);
            }
          }
        }
      }
      ids.add(saveVNFD(vnfd, projectId, vnfScripts, link));
    }
    nsd.getVnfd().clear();

    for (String id : ids) {

      String vnfdId = "";
      Iterable<VirtualNetworkFunctionDescriptor> vnfds = vnfdRepository.findByProjectId(projectId);
      for (VirtualNetworkFunctionDescriptor vnfd : vnfds) {
        if (vnfd.getVnfPackageLocation().equals(id)) {

          vnfdId = vnfd.getId();
        }
      }

      VirtualNetworkFunctionDescriptor vnfd = new VirtualNetworkFunctionDescriptor();
      vnfd.setId(vnfdId);
      nsd.getVnfd().add(vnfd);
    }

    input.close();
    this.template.close();
    this.metadata.close();

    return nsd;
  }
}
