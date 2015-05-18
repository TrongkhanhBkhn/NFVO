package org.project.neutrino.nfvo.api;

import org.project.neutrino.nfvo.catalogue.nfvo.Datacenter;
import org.project.neutrino.nfvo.core.interfaces.DatacenterManagement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * @author dbo
 *
 */
@RestController
@RequestMapping("/datacenters")
public class RestDatacenter {

	private Logger log = LoggerFactory.getLogger(this.getClass());

	@Autowired
	private DatacenterManagement datacenterManagement;

	/**
	 * Adds a new Datacenter to the Datacenters repository
	 * @param datacenter
	 * @return datacenter
	 */
	@RequestMapping(method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public Datacenter create(@RequestBody @Valid Datacenter datacenter) {
		return datacenterManagement.add(datacenter);
	}

	/**
	 * Removes the Datacenter from the Datacenter repository
	 * 
	 * @param id: The Datacenter's id to be deleted
	 */
	@RequestMapping(value = "{id}", method = RequestMethod.DELETE)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable("id") String id) {
		datacenterManagement.delete(id);
	}

	/**
	 * Returns the list of the Datacenters available
	 * @return List<Datacenter>: The List of Datacenters available
	 */
	@RequestMapping(method = RequestMethod.GET)
	public List<Datacenter> findAll() {
		return datacenterManagement.query();
	}

	/**
	 * Returns the Datacenter selected by id
	 * @param id: The Datacenter's id selected
	 * @return Datacenter: The Datacenter selected
	 */
	@RequestMapping(value = "{id}", method = RequestMethod.GET)
	public Datacenter findById(@PathVariable("id") String id) {
		Datacenter datacenter = datacenterManagement.query(id);

		return datacenter;
	}

	/**
	 * Updates the Datacenter
	 * @param new_datacenter: The Datacenter to be updated
	 * @param id: The Datacenter's id selected
	 * @return Datacenter: The Datacenter updated
	 */

	@RequestMapping(value = "{id}", method = RequestMethod.PUT, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	@ResponseStatus(HttpStatus.ACCEPTED)
	public Datacenter update(@RequestBody @Valid Datacenter new_datacenter,
			@PathVariable("id") String id) {
		return datacenterManagement.update(new_datacenter, id);
	}
}
