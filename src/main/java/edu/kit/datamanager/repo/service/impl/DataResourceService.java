/*
 * Copyright 2018 Karlsruhe Institute of Technology.
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
 */
package edu.kit.datamanager.repo.service.impl;

import com.github.fge.jsonpatch.JsonPatch;
import edu.kit.datamanager.entities.Identifier;
import edu.kit.datamanager.entities.PERMISSION;
import edu.kit.datamanager.entities.messaging.DataResourceMessage;
import edu.kit.datamanager.exceptions.BadArgumentException;
import edu.kit.datamanager.exceptions.GoneException;
import edu.kit.datamanager.exceptions.ResourceAlreadyExistException;
import edu.kit.datamanager.exceptions.ResourceNotFoundException;
import edu.kit.datamanager.exceptions.UpdateForbiddenException;
import edu.kit.datamanager.repo.configuration.RepoBaseConfiguration;
import edu.kit.datamanager.repo.dao.IAllIdentifiersDao;
import edu.kit.datamanager.repo.dao.IDataResourceDao;
import edu.kit.datamanager.repo.dao.spec.dataresource.LastUpdateSpecification;
import edu.kit.datamanager.repo.dao.spec.dataresource.StateSpecification;
import edu.kit.datamanager.repo.domain.Agent;
import edu.kit.datamanager.repo.domain.AllIdentifiers;
import edu.kit.datamanager.repo.domain.DataResource;
import edu.kit.datamanager.repo.domain.PrimaryIdentifier;
import edu.kit.datamanager.repo.domain.UnknownInformationConstants;
import edu.kit.datamanager.repo.domain.acl.AclEntry;
import edu.kit.datamanager.repo.service.IDataResourceService;
import edu.kit.datamanager.repo.util.DataResourceUtils;
import edu.kit.datamanager.repo.util.SpecUtils;
import edu.kit.datamanager.service.IMessagingService;
import edu.kit.datamanager.service.impl.LogfileMessagingService;
import edu.kit.datamanager.util.AuthenticationHelper;
import edu.kit.datamanager.util.ControllerUtils;
import edu.kit.datamanager.util.PatchUtil;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service implementation for the IDataResourceService interface.
 *
 * @author jejkal
 */
public class DataResourceService implements IDataResourceService {

   
    @Autowired
    private IDataResourceDao dao;
    @Autowired
    private IAllIdentifiersDao allIdentifiersDao;

    private static final Logger logger = LoggerFactory.getLogger(DataResourceService.class);
    
    private RepoBaseConfiguration applicationProperties;
    
    /**
     * Optional messagingService bean may or may not be available, depending on
     * a service's configuration. If messaging capabilities are disabled, this
     * bean should be not available. In that case, messages are only logged.
     */
    @Autowired
    private Optional<IMessagingService> messagingService;

    @PersistenceContext
    private EntityManager em;

      
   /* @Autowired
    public DataResourceService(IDataResourceDao dao, IAllIdentifiersDao allIdentifiersDao, Optional<IMessagingService> messagingService) {
        this.dao = dao;
        this.allIdentifiersDao = allIdentifiersDao;
        this.messagingService = messagingService;
    }*/

    /**
     * Default constructor.
     */
    public DataResourceService() {
    }

    @Override
    public void configure(RepoBaseConfiguration applicationProperties) {
        this.applicationProperties = applicationProperties;
        printInfo("configure");
    }

    @Override
    @Transactional
    public DataResource create(DataResource resource, String callerPrincipal) {
        return create(resource, callerPrincipal, null, null);
    }

    @Override
    @Transactional
    public DataResource create(DataResource resource, String callerPrincipal, String callerFirstName, String callerLastName) {
        logger.trace("Performing create({}, {}, {}, {}).", resource, callerPrincipal, callerFirstName, callerLastName);
        printInfo("create");

        //reset id as external assignment of ids is not allowed
        resource.setId(null);
        //check for provided DOI
        boolean hasDoi = true;
        boolean hasOtherIdentifier = false;
        if (resource.getIdentifier() == null) {
            hasDoi = false;
        } else {
            for (UnknownInformationConstants constant : UnknownInformationConstants.values()) {
                if (constant.getValue().equals(resource.getIdentifier().getValue())) {
                    hasOtherIdentifier = true;
                    hasDoi = false;
                    break;
                }
            }
        }

        if (!hasDoi) {
            logger.debug("No primary identifier assigned to resource. Using placeholder '{}'.", UnknownInformationConstants.TO_BE_ASSIGNED_OR_ANNOUNCED_LATER);
            //set placeholder identifier

            if (!hasOtherIdentifier) {
                resource.setIdentifier(PrimaryIdentifier.factoryPrimaryIdentifier(UnknownInformationConstants.TO_BE_ASSIGNED_OR_ANNOUNCED_LATER.getValue()));
            }

            //check alternate identifiers for internal identifier
            boolean hasAlternateInternalIdentifier = false;
            for (Identifier alt : resource.getAlternateIdentifiers()) {
                if (Identifier.IDENTIFIER_TYPE.INTERNAL.equals(alt.getIdentifierType())) {
                    if (alt.getValue() == null) {
                        logger.error("Found alternate identifier of type INTERNAL with value 'null'. Throwing BadArgumentException.");
                        throw new BadArgumentException("Provided internal identifier must not be null.");
                    }
                    logger.debug("Setting resource identifier to provided internal identifier with value {}.", alt.getValue());
                    resource.setId(alt.getValue());
                    testForConflictingIdentifiers(resource);
                    hasAlternateInternalIdentifier = true;
                    break;
                }
            }

            if (!hasAlternateInternalIdentifier) {
                boolean success = false;
                // Test a maximum of 10 times for a new UUID
                for (int tryToFindIdentifier = 0; !success && (tryToFindIdentifier < 10); tryToFindIdentifier++) {
                    String altId = UUID.randomUUID().toString();

                    logger.debug("No primary identifier assigned to resource and no alternate identifier of type INTERNAL was found. Assigning alternate INTERNAL identifier {}.", altId);
                    Identifier internalIdentifier = Identifier.factoryInternalIdentifier(altId);
                    resource.getAlternateIdentifiers().add(internalIdentifier);
                    resource.setId(altId);
                    try {
                        testForConflictingIdentifiers(resource);
                        success = true;
                    } catch (Throwable tw) {
                        // remove created internal identifier.
                        resource.getAlternateIdentifiers().remove(internalIdentifier);
                    }
                }
            }
        } else {
            logger.debug("Primary or other identifier found. Setting resource identifier to primary identifier {}.", resource.getIdentifier().getValue());
            resource.setId(resource.getIdentifier().getValue());
        }
        checkForConflicts(resource.getId());
        logger.trace("Checking for mandatory element 'titles'.");
        if (resource.getTitles().isEmpty()) {
            logger.error("No titles found. Throwing BadArgumentException.");
            throw new BadArgumentException("Mandatory attribute 'title' missing.");
        }

        logger.trace("Checking for mandatory element 'resourceType'.");
        if (resource.getResourceType() == null) {
            logger.error("No resource type provided found. Throwing BadArgumentException.");
            throw new BadArgumentException("Mandatory attribute 'resourceType' missing.");
        }

        logger.trace("Checking for mandatory element 'creators'.");
        //check mandatory datacite attributes
        if (resource.getCreators().isEmpty()) {
            logger.trace("No creators found. Adding creator based on authentication context.");

            Agent creator = new Agent();
            if (callerFirstName == null && callerLastName == null) {
                logger.trace("Both, first and last name of authentication context are 'null'. Using caller principal '{}' as first name.", callerPrincipal);
                creator.setGivenName(callerPrincipal);
                creator.setFamilyName(null);
            } else {
                logger.trace("Setting firstname {} and lastname {} as caller.", callerFirstName, callerLastName);
                creator.setGivenName(callerFirstName);
                creator.setFamilyName(callerLastName);
            }
            logger.debug("Adding new creator {} to resource.", creator);
            resource.getCreators().add(creator);
        }

        logger.trace("Checking for mandatory element 'publisher'.");
        //set auto-generateable fields
        if (resource.getPublisher() == null) {
            logger.debug("Setting caller principal {} as publisher.", callerPrincipal);
            resource.setPublisher(callerPrincipal);
        }

        logger.trace("Checking for mandatory element 'publicationYear'.");
        if (resource.getPublicationYear() == null) {
            String thisYear = Integer.toString(Calendar.getInstance().get(Calendar.YEAR));
            logger.debug("Setting current year {} as publicationYear.", thisYear);
            resource.setPublicationYear(thisYear);
        }

        logger.trace("Checking resource for caller acl entry.");
        //check ACLs for caller
        AclEntry callerEntry = null;
        for (AclEntry entry : resource.getAcls()) {
            if (callerPrincipal.equals(entry.getSid())) {
                logger.trace("Acl entry for caller {} found: {}", callerPrincipal, entry);
                callerEntry = entry;
                break;
            }
        }

        if (callerEntry == null) {
            logger.debug("Adding caller entry with ADMINISTRATE permissions.");
            callerEntry = new AclEntry(callerPrincipal, PERMISSION.ADMINISTRATE);
            resource.getAcls().add(callerEntry);
        } else {
            logger.debug("Ensuring ADMINISTRATE permissions for acl entry {}.", callerEntry);
            //make sure at least the caller has administrate permissions
            callerEntry.setPermission(PERMISSION.ADMINISTRATE);
        }
        logger.trace("Checking for creation date.");
        boolean haveCreationDate = false;
        for (edu.kit.datamanager.repo.domain.Date d : resource.getDates()) {
            if (edu.kit.datamanager.repo.domain.Date.DATE_TYPE.CREATED.equals(d.getType())) {
                logger.trace("Creation date entry found.");
                haveCreationDate = true;
                break;
            }
        }

        if (!haveCreationDate) {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            logger.debug("Adding current date {} as creation date.", now);
            edu.kit.datamanager.repo.domain.Date creationDate = new edu.kit.datamanager.repo.domain.Date();
            creationDate.setType(edu.kit.datamanager.repo.domain.Date.DATE_TYPE.CREATED);
            creationDate.setValue(now);
            resource.getDates().add(creationDate);
        }

        logger.trace("Checking resource state.");
        if (Objects.isNull(resource.getState())) {
            logger.debug("Setting initial resource state to {}.", DataResource.State.VOLATILE);
            resource.setState(DataResource.State.VOLATILE);
        } else {
            logger.trace("Resource state found. State is: {}", resource.getState());
        }

        logger.trace("Setting resource's lastUpdate to now().");
        resource.setLastUpdate(Instant.now().truncatedTo(ChronoUnit.MILLIS));

        logger.trace("Persisting created resource.");
        resource = getDao().save(resource);
        saveIdentifiers(resource);

        logger.trace("Capturing audit information.");
        applicationProperties.getAuditService().captureAuditInformation(resource, AuthenticationHelper.getPrincipal());

        logger.trace("Sending CREATE event.");
        messagingService.orElse(new LogfileMessagingService()).send(DataResourceMessage.factoryCreateMessage(resource.getId(), AuthenticationHelper.getPrincipal(), ControllerUtils.getLocalHostname()));
        return resource;
    }

    @Override
    public Page<DataResource> findAllVersions(final String id, Pageable pgbl) {
        logger.trace("Performing findAllVersions({}).", id);
        printInfo("findById");
        DataResource result = findById(id);
        // Check for READ permission
        DataResourceUtils.performPermissionCheck(result, PERMISSION.READ);

        long lastVersion = applicationProperties.getAuditService().getCurrentVersion(result.getId());
        boolean versioningEnabled = applicationProperties.isAuditEnabled();

        long startIndex = lastVersion;
        long noOfElements = startIndex;

        if (pgbl != null) {
            noOfElements = pgbl.getPageSize();
            startIndex = startIndex - pgbl.getOffset();
            if (startIndex < noOfElements) {
                noOfElements = startIndex;
            }
        } else {
            pgbl = PageRequest.of(0, (int) lastVersion);
        }
        List<DataResource> list = new ArrayList<>();
        if (versioningEnabled) {
            for (long version = startIndex; version > 0; version--) {
                Optional<DataResource> resource = applicationProperties.getAuditService().getResourceByVersion(result.getId(), version);
                if (resource.isPresent()) {
                    list.add(resource.get());
                }
            }
        } else {
            list.add(result);
        }
        Page<DataResource> page = new PageImpl<>(list, pgbl, lastVersion);
        return page;
    }

    @Override
    public DataResource findById(final String id) {
        logger.trace("Performing findById({}).", id);
        printInfo("findById");
        long nano1 = System.nanoTime() / 1000000;
        Optional<DataResource> result = getDao().findById(id);

        long nano2 = System.nanoTime() / 1000000;
        long nano3 = nano2;
        if (!result.isPresent()) {
            String resourceId = getResourceIdFromAnyId(id);
            logger.trace("Resource ID: {} -> {}", id, resourceId);
            nano3 = System.nanoTime() / 1000000;
            result = getDao().findById(resourceId);
            if (!result.isPresent()) {
                logger.error("No data resource found for identifier {}. Throwing ResourceNotFoundException.", id);
                throw new ResourceNotFoundException("Data resource with id " + id + " was not found.");
            } else {
                logger.info("Please use '{}' instead of '{}' for faster access!", resourceId, id);
            }
        }
        long nano4 = System.nanoTime() / 1000000;
        logger.trace("Find by id, {}, {}, {}, {}", nano1, nano2 - nano1, nano3 - nano1, nano4 - nano1);
        return result.get();
    }

    @Override
    public DataResource findByAnyIdentifier(final String resourceIdentifier) {
        logger.trace("Performing findByAnyIdentifier({}).", resourceIdentifier);
        printInfo("findByAnyIdentifier");
        return findByAnyIdentifier(resourceIdentifier, null);
    }

    @Override
    public DataResource findByAnyIdentifier(final String resourceIdentifier, Long version) {
        logger.trace("Performing findByAnyIdentifier({}, {}).", resourceIdentifier, version);
        // first of all try to get resourceID
        long nano1 = System.nanoTime() / 1000000;
        DataResource resource = findById(resourceIdentifier);
        //perform read check for most recent version of resource
        //if not access allowed, already stop here
        DataResourceUtils.performPermissionCheck(resource, PERMISSION.READ);
        
        String correctResourceId = resource.getId();
        long nano2 = System.nanoTime() / 1000000;
        if (Objects.nonNull(version)) {
            logger.trace("Obtained resource for identifier {}. Checking for shadow of version {}.", correctResourceId, version);
            Optional<DataResource> optAuditResult = applicationProperties.getAuditService().getResourceByVersion(resource.getId(), version);
            if (optAuditResult.isPresent()) {
                logger.trace("Shadow successfully obtained. Returning version {} of resource with id {}.", version, correctResourceId);
                return optAuditResult.get();
            } else {
                logger.info("Version {} of resource {} not found. Returning HTTP 404 (NOT_FOUND).", version, correctResourceId);
                throw new ResourceNotFoundException("Data resource with identifier " + correctResourceId + " is not available in version " + version + ".");

            }
        }
        long nano3 = System.nanoTime() / 1000000;

        logger.trace("Find by any identifier, {}, {}, {}", nano1, nano2 - nano1, nano3 - nano1);

        return resource;
    }

    @Override
    public Page<DataResource> findByExample(DataResource example,
            Instant lastUpdateFrom,
            Instant lastUpdateUntil,
            List<String> callerIdentities,
            boolean callerIsAdministrator, Pageable pgbl
    ) {
        logger.trace("Performing findByExample({}, {}, {}, {}, {}, {}).", example, lastUpdateFrom, lastUpdateUntil, callerIdentities, callerIsAdministrator, pgbl);
        Page<DataResource> page;
        if (callerIsAdministrator) {
            //do find all
            logger.trace("Administrator access detected. Calling findAll({}, {}, {}).", example, pgbl, Boolean.TRUE);
            page = findAll(example, lastUpdateFrom, lastUpdateUntil, pgbl, true);
        } else {
            //query based on membership
            if (example != null && DataResource.State.REVOKED.equals(example.getState())) {
                logger.debug("Removing 'REVOKED' state from example due to unprivileged request.");
                example.setState(null);
            }
            logger.trace("Non-Administrator access detected. Calling findAllFiltered({}, {}, {}, {}, {}).", example, callerIdentities, PERMISSION.READ, pgbl, Boolean.FALSE);
            page = findAllFiltered(example, lastUpdateFrom, lastUpdateUntil, callerIdentities, PERMISSION.READ, pgbl, false);
        }

        logger.trace("Returning page content.");
        return page;
    }

    @Override
    public Page<DataResource> findAllFiltered(DataResource example,
            Instant lastUpdateFrom,
            Instant lastUpdateUntil,
            List<String> sids,
            PERMISSION permission, Pageable pgbl,
            boolean includeRevoked
    ) {
        logger.trace("Performing findAllFiltered({}, {}, {}, {}, {}).", example, sids, permission, pgbl, includeRevoked);
        Specification<DataResource> spec = SpecUtils.getByExampleSpec(example, em, sids, permission);

        spec = (spec == null) ? LastUpdateSpecification.toSpecification(lastUpdateFrom, lastUpdateUntil) : spec.and(LastUpdateSpecification.toSpecification(lastUpdateFrom, lastUpdateUntil));

        return doFind(spec, example, pgbl, includeRevoked);
    }

    @Override
    public Page<DataResource> findAll(DataResource example,
            Pageable pgbl,
            boolean pIncludeRevoked
    ) {
        logger.trace("Performing findAll({}, {}, {}).", example, pgbl, pIncludeRevoked);
        return findAll(example, null, null, pgbl, pIncludeRevoked);
    }

    @Override
    public Page<DataResource> findAll(DataResource example,
            Instant lastUpdateFrom,
            Instant lastUpdateUntil,
            Pageable pgbl) {
        logger.trace("Performing findAll({}, {}, {}, {}).", example, lastUpdateFrom, lastUpdateUntil, pgbl);
        Specification<DataResource> spec = SpecUtils.getByExampleSpec(example, em, null, null);

        spec = (spec == null) ? LastUpdateSpecification.toSpecification(lastUpdateFrom, lastUpdateUntil) : spec.and(LastUpdateSpecification.toSpecification(lastUpdateFrom, lastUpdateUntil));

        return doFind(spec, example, pgbl, false);
    }

    @Override
    public Page<DataResource> findAll(DataResource example,
            Instant lastUpdateFrom,
            Instant lastUpdateUntil,
            Pageable pgbl,
            boolean includeRevoked) {
        logger.trace("Performing findAll({}, {}, {}, {}).", example, lastUpdateFrom, lastUpdateUntil, pgbl);
        Specification<DataResource> spec = SpecUtils.getByExampleSpec(example, em, null, null);

        spec = (spec == null) ? LastUpdateSpecification.toSpecification(lastUpdateFrom, lastUpdateUntil) : spec.and(LastUpdateSpecification.toSpecification(lastUpdateFrom, lastUpdateUntil));

        return doFind(spec, example, pgbl, includeRevoked);
    }

    @Override
    public Page<DataResource> findAll(DataResource resource, Pageable pgbl) {
        logger.trace("Performing findAll({}).", "DataResource#" + resource.getId());
        return findAll(resource, pgbl, false);
    }

    /**
     * Private helper used by findAll and findAllFiltered.
     */
    private Page<DataResource> doFind(
            Specification<DataResource> spec,
            DataResource example,
            Pageable pgbl,
            boolean includeRevoked
    ) {
        logger.trace("Performing doFind({}, {}, {}).", spec, pgbl, includeRevoked);

        List<DataResource.State> states = new ArrayList<>();
        logger.trace("Checking example for state information.");
        if (example != null && example.getState() != null) {
            //example is set...check if example state should be used
            if (includeRevoked || !DataResource.State.REVOKED.equals(example.getSubjects())) {
                logger.trace("Adding state {} from example.", example.getState());
                //we either are allowed to include revoked state or the state is not 'REVOKED', add state from example
                states.add(example.getState());
            } else {
                logger.debug("Ignoring state {} from example as 'includeRevoked' is set {}.", example.getState(), includeRevoked);
            }
        }

        if (states.isEmpty()) {
            logger.trace("No state element received from example. Adding default states VOLATILE and FIXED.");
            //No state obtained from example...adding default states VOLATILE and FIXED
            states.add(DataResource.State.VOLATILE);
            states.add(DataResource.State.FIXED);
        }

        if (includeRevoked) {
            logger.trace("Flag 'includeRevoked' is enabled. Adding states REVOKED.");
            //Add REVOKED state in case this is allowed (e.g. admin access)
            states.add(DataResource.State.REVOKED);
        }

        logger.trace("Adding state spec for states {}.", states);
        if (spec == null) {
            logger.trace("Specification is currently null. Setting specification to StateSpecification.");
            //spec is currently null, therefore only the StateSpec is used
            spec = StateSpecification.toSpecification(states);
        } else {
            logger.trace("Appending StateSpecification via AND operator.");
            //spec is not null, connect StateSpec by AND
            spec = spec.and(StateSpecification.toSpecification(states));
        }

        logger.trace("Querying DAO implementation using final spec and pageable information {}.", pgbl);
        return getDao().findAll(spec, pgbl);
    }

    @Override
    @Transactional
    public void patch(DataResource resource, JsonPatch patch,
            Collection<? extends GrantedAuthority> userGrants) {
        logger.trace("Performing patch({}, {}, {}).", "DataResource#" + resource.getId(), patch, userGrants);

        List<String> identifierListBefore = getUniqueIdentifiers(resource);
        logger.trace("Resource identifiers before patch: {}", identifierListBefore);

        DataResource updated = PatchUtil.applyPatch(resource, patch, DataResource.class, userGrants);
        List<String> identifierListAfter = getUniqueIdentifiers(updated);
        logger.trace("Resource identifiers after patch: {}", identifierListAfter);

        checkUniqueIdentifiers(identifierListBefore, identifierListAfter);

        logger.trace("Setting resource's lastUpdate to now().");
        updated.setLastUpdate(Instant.now().truncatedTo(ChronoUnit.MILLIS));

        logger.trace("Persisting updated resource.");
        DataResource result = getDao().save(updated);
        saveIdentifiers(result);

        logger.trace("Capturing audit information.");
        applicationProperties.getAuditService().captureAuditInformation(result, AuthenticationHelper.getPrincipal());

        logger.trace("Sending UPDATE event.");
        messagingService.orElse(new LogfileMessagingService()).send(DataResourceMessage.factoryUpdateMessage(resource.getId(), AuthenticationHelper.getPrincipal(), ControllerUtils.getLocalHostname()));
    }

    @Override
    @Transactional
    public DataResource put(DataResource resource, DataResource newResource,
            Collection<? extends GrantedAuthority> userGrants) throws UpdateForbiddenException {
        logger.trace("Performing put({}, {}, {}).", "DataResource#" + resource.getId(), "DataResource#" + newResource.getId(), userGrants);
        List<String> identifierListBefore = getUniqueIdentifiers(resource);
        logger.trace("Resource identifiers before update: {}", identifierListBefore);
        List<String> identifierListAfter = getUniqueIdentifiers(newResource);
        logger.trace("Resource identifiers after update: {}", identifierListAfter);

        checkUniqueIdentifiers(identifierListBefore, identifierListAfter);

        //check if any forbidden field has been updated
        //use PatchUtil as it does exactly this check
        if (!PatchUtil.canUpdate(resource, newResource, userGrants)) {
            String message = "Update not applicable. At least one unmodifiable field has been changed.";
            logger.info(message);
            throw new UpdateForbiddenException(message);
        }

        StringBuilder errorMessage = new StringBuilder();
        if (newResource.getAcls() == null || newResource.getAcls().isEmpty()) {
            errorMessage.append("Empty ACL provided for update.\n");
        }

        if (newResource.getTitles() == null || newResource.getTitles().isEmpty()) {
            errorMessage.append("Empty title list provided for update.\n");
        }
        if (newResource.getCreators() == null || newResource.getCreators().isEmpty()) {
            errorMessage.append("Empty creators list provided for update.\n");
        }
        if (newResource.getPublicationYear() == null) {
            errorMessage.append("Empty publication year provided for update.\n");
        }
        if (newResource.getPublisher() == null) {
            errorMessage.append("Empty publication year provided for update.\n");
        }

        if (errorMessage.length() > 0) {
            logger.info(errorMessage.toString());
            throw new BadArgumentException(errorMessage.toString());
        }

        logger.trace("Setting resource's lastUpdate to now().");
        newResource.setLastUpdate(Instant.now().truncatedTo(ChronoUnit.MILLIS));

        DataResource result = getDao().save(newResource);
        saveIdentifiers(result);

        logger.trace("Capturing audit information.");
        applicationProperties.getAuditService().captureAuditInformation(result, AuthenticationHelper.getPrincipal());

        logger.trace("Sending UPDATE event.");
        messagingService.orElse(new LogfileMessagingService()).send(DataResourceMessage.factoryUpdateMessage(resource.getId(), AuthenticationHelper.getPrincipal(), ControllerUtils.getLocalHostname()));
        return result;
    }

    @Override
    public Optional<String> getAuditInformationAsJson(String resourceIdentifier, Pageable pgbl) {
        logger.trace("Performing getAuditInformation({}, {}).", resourceIdentifier, pgbl);
        return applicationProperties.getAuditService().getAuditInformationAsJson(resourceIdentifier, pgbl.getPageNumber(), pgbl.getPageSize());
    }

    /**
     * Get all identifiers of a resource that have to be unique, e.g. primary
     * and alternate identifiers.
     *
     * @param resource The resource.
     *
     * @return A list of identifiers.
     */
    private List<String> getUniqueIdentifiers(DataResource resource) {
        List<String> identifiers = new ArrayList<>();
        if (resource.getIdentifier() != null) {
            identifiers.add(resource.getIdentifier().getValue());
        }
        resource.getAlternateIdentifiers().forEach((alt) -> {
            identifiers.add(alt.getValue());
        });
        return identifiers;
    }

    /**
     * Check if there is a resource identified with one identifiers from list
     * 'after' not in 'before'. If at least one resource was found, a
     * ResourceAlreadyExistsException is throws avoiding any update of the
     * resource with the new list of identifiers.
     *
     * @param before The list of unique identifiers before an update.
     * @param after The list of unique identifiers after an update.
     */
    private void checkUniqueIdentifiers(List<String> before, List<String> after) {
        logger.trace("Removing assigned identifiers {} from list of checked identifiers {}.", before, after);
        after.removeAll(before);
        logger.trace("Remaining new or updated resource identifiers: {}", after);

        String[] afterList = removePredifinedIdentifiers(after);
        checkForConflicts(afterList);
    }

    /**
     * Check for predefined identifiers which are not set. Attention. This will
     * change the list!
     *
     * @param newIdentifiers The list of unique identifiers before an update.
     * @return unique identifier as string array.
     */
    private String[] removePredifinedIdentifiers(List<String> newIdentifiers) {
        logger.trace("Check for new or updated resource identifiers: {}", newIdentifiers);
        String[] identifierArray = new String[0];
        if (newIdentifiers.isEmpty()) {
            return identifierArray;
        }
        List<String> temporaryValues = new ArrayList<>();
        for (UnknownInformationConstants item : UnknownInformationConstants.values()) {
            temporaryValues.add(item.getValue());
        }
        newIdentifiers.removeAll(temporaryValues);
        identifierArray = newIdentifiers.toArray(new String[newIdentifiers.size()]);

        return identifierArray;
    }

    @Override
    @Transactional(readOnly = false)
    public void delete(DataResource resource) {
        logger.trace("Performing delete({}).", "DataResource#" + resource.getId());

        DataResource.State newState = DataResource.State.REVOKED;

        if (DataResource.State.REVOKED.equals(resource.getState())) {
            newState = DataResource.State.GONE;
            logger.trace("DELETE was called on revoked resource. Setting new state to {}.", newState);
        }
        logger.debug("Setting resource state to {}.", newState);
        resource.setState(newState);

        logger.trace("Setting resource's lastUpdate to now().");
        resource.setLastUpdate(Instant.now().truncatedTo(ChronoUnit.MILLIS));

        logger.trace("Persisting resource.");
        DataResource result = getDao().save(resource);
        saveIdentifiers(result);
        //capture state change, not a delete operation as the resource is not physically deleted
        logger.trace("Capturing audit information.");
        applicationProperties.getAuditService().captureAuditInformation(result, AuthenticationHelper.getPrincipal());
    }

    @Override
    @Transactional(readOnly = false)
    public DataResource restore(DataResource resource) {
        logger.trace("Performing restore({}).", "DataResource#" + resource.getId());

        DataResource.State newState = DataResource.State.VOLATILE;
        logger.debug("Setting resource state to {}.", newState);
        resource.setState(newState);

        logger.trace("Setting resource's lastUpdate to now().");
        resource.setLastUpdate(Instant.now().truncatedTo(ChronoUnit.MILLIS));

        logger.trace("Persisting resource.");
        DataResource result = getDao().save(resource);
        saveIdentifiers(result);
        //capture state change, not a delete operation as the resource is not physically deleted
        logger.trace("Capturing audit information.");
        applicationProperties.getAuditService().captureAuditInformation(result, AuthenticationHelper.getPrincipal());
        
        return result;
    }

    protected IDataResourceDao getDao() {
        return dao;
    }

    @Override
    public Health health() {
        logger.trace("Obtaining health information.");
        return Health.up().withDetail("DataResources", getDao().count()).withDetail("Audit enabled?", applicationProperties.isAuditEnabled()).build();
    }

    public void testForConflictingIdentifiers(DataResource newResource) {
        List<String> uniqueIdentifiers = getUniqueIdentifiers(newResource);

        String[] afterList = removePredifinedIdentifiers(uniqueIdentifiers);
        checkForConflicts(afterList);
    }

    private void printInfo(String message) {
        if (logger.isTraceEnabled()) {
            logger.trace("----------------------------------------------------------------------------------");
            logger.trace(message + " -> " + this);
            logger.trace("----------------------------------------------------------------------------------");
        }
    }

    private void checkForConflicts(String... identifiers) {
        String allIdentifiers = Arrays.toString(identifiers);
        printInfo("checkForConflicts: " + allIdentifiers);
        //check resource by identifier
        List<String> identifierList = new ArrayList<>();
        Collections.addAll(identifierList, identifiers);
        long cnt = allIdentifiersDao.countByIdentifierIn(identifierList);
        logger.trace("Found {} existing resources conflicting with provided identifier {}.", cnt, allIdentifiers);
        if (cnt > 0) {
            logger.trace("Check if gone...");
            cnt = allIdentifiersDao.countByIdentifierInAndStatus(identifierList, DataResource.State.GONE);
            if (cnt > 0) {
                String message = String.format("Resource '%s' already gone!", allIdentifiers);
                logger.error(message);
                throw new GoneException(message);
            } else {
                logger.error("Number of conflicting identifiers with identifier {} is neq 0. Throwing ResourceAlreadyExistException.", allIdentifiers);
                throw new ResourceAlreadyExistException("There is already a resource with at least one of the following identifiers " + allIdentifiers);
            }
        }
//    Specification<DataResource> spec = AlternateIdentifierSpec.toSpecification(identifiers).
//            or(PrimaryIdentifierSpec.toSpecification(identifiers)).
//            or(InternalIdentifierSpec.toSpecification(identifiers));
//    long cnt = getDao().count(spec);
//    logger.trace("Found {} existing resources conflicting with provided identifier {}.", cnt, allIdentifiers);
//    if (cnt != 0) {
//      logger.trace("Check if gone...");
//      List<DataResource.State> states = new ArrayList<>();
//      states.add(DataResource.State.GONE);
//      spec = StateSpecification.toSpecification(states).and(spec);
//      if (getDao().count(spec) != 0) {
//        String message = String.format("Resource '%s' already gone!", allIdentifiers);
//        logger.error(message);
//        throw new GoneException(message);
//      } else {
//        logger.error("Number of conflicting identifiers with identifier {} is neq 0. Throwing ResourceAlreadyExistException.", allIdentifiers);
//        throw new ResourceAlreadyExistException("There is already a resource with identifier " + allIdentifiers);
//      }
//    }
    }

    public void saveIdentifiers(DataResource dataResource) {
        List<String> uniqueIdentifiers = getUniqueIdentifiers(dataResource);
        String[] remainingIdentifiers = removePredifinedIdentifiers(uniqueIdentifiers);
        saveIdentifiers(remainingIdentifiers, dataResource.getId(), dataResource.getState());
    }

    private void saveIdentifiers(String[] identifiers, String resource, DataResource.State state) {
        AllIdentifiers result;
        for (String identifier : identifiers) {
            result = new AllIdentifiers();
            result.setIdentifier(identifier);
            result.setResourceId(resource);
            result.setStatus(state);
            allIdentifiersDao.save(result);
        }
    }

    private String getResourceIdFromAnyId(String resourceIdentifier) {
        Optional<AllIdentifiers> helperResource = allIdentifiersDao.findById(resourceIdentifier);
        if (!helperResource.isPresent()) {
            String message = "Data resource with identifier " + resourceIdentifier + " was not found.";
            logger.info(message);
            throw new ResourceNotFoundException(message);
        }
        return helperResource.get().getResourceId();
    }
}
