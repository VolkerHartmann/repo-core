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
package edu.kit.datamanager.repo.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.jsonpatch.JsonPatch;
import edu.kit.datamanager.controller.hateoas.event.PaginatedResultsRetrievedEvent;
import edu.kit.datamanager.entities.Identifier;
import edu.kit.datamanager.entities.PERMISSION;
import edu.kit.datamanager.entities.RepoServiceRole;
import edu.kit.datamanager.entities.RepoUserRole;
import edu.kit.datamanager.exceptions.AccessForbiddenException;
import edu.kit.datamanager.exceptions.CustomInternalServerError;
import edu.kit.datamanager.exceptions.ResourceElsewhereException;
import edu.kit.datamanager.exceptions.ResourceNotFoundException;
import edu.kit.datamanager.exceptions.ServiceUnavailableException;
import edu.kit.datamanager.exceptions.UpdateForbiddenException;
import edu.kit.datamanager.repo.configuration.RepoBaseConfiguration;
import edu.kit.datamanager.repo.domain.DataResource;
import edu.kit.datamanager.repo.domain.acl.AclEntry;
import edu.kit.datamanager.util.AuthenticationHelper;
import edu.kit.datamanager.util.ControllerUtils;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.util.UriComponentsBuilder;

/**
 *
 * @author jejkal
 */
public class DataResourceUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataResourceUtils.class);
    /**
     * Mapper for parsing json.
     */
    private static ObjectMapper mapper = new ObjectMapper();

    private DataResourceUtils() {
    }

    /**
     * Create a new data resource.
     *
     * @param applicationProperties
     * @param resource
     * @return
     */
    public static DataResource createResource(RepoBaseConfiguration applicationProperties,
            DataResource resource) {
        if (applicationProperties.isReadOnly()) {
            String message = "Repository is in read-only mode. Create request denied.";
            LOGGER.info(message);
            throw new ServiceUnavailableException(message);
        }

        ControllerUtils.checkAnonymousAccess();

        DataResource result = applicationProperties.getDataResourceService().create(resource,
                (String) AuthenticationHelper.getAuthentication().getPrincipal(),
                AuthenticationHelper.getFirstname(),
                AuthenticationHelper.getLastname());
        return result;
    }

    /**
     * Read an existing resource.
     *
     * @param applicationProperties
     * @param identifier
     * @param version
     * @param supplier
     * @return
     */
    public static ResponseEntity<DataResource> readResource(RepoBaseConfiguration applicationProperties,
            String identifier,
            Long version,
            Function<String, String> supplier) {
        DataResource resource;
        resource = getResourceByIdentifierOrRedirect(applicationProperties, identifier, version, supplier);
        DataResourceUtils.performPermissionCheck(resource, PERMISSION.READ);

        long currentVersion = applicationProperties.getAuditService().getCurrentVersion(identifier);

        if (currentVersion > 0) {
            //trigger response creation and set etag...the response body is set automatically
            return ResponseEntity.ok().eTag("\"" + resource.getEtag() + "\"").header("Resource-Version", Long.toString((version != null) ? version : currentVersion)).body(filterResource(resource));
        } else {
            return ResponseEntity.ok().eTag("\"" + resource.getEtag() + "\"").body(filterResource(resource));
        }
    }

    /**
     * Read all versions of an existing resource. If versioning is disabled only
     * the current state is available. If any error occurs an exception is
     * thrown.
     *
     * @param applicationProperties
     * @param identifier resource ID of the resource.
     * @param pgbl
     * @return Page instance holding all versions or current state if version is
     * disabled.
     */
    public static Page<DataResource> readAllVersionsOfResource(RepoBaseConfiguration applicationProperties,
            String identifier,
            Pageable pgbl) {
        Page<DataResource> page = applicationProperties.getDataResourceService().findAllVersions(identifier, pgbl);
        return page;
    }

    /**
     * Read an existing resource.
     *
     * @param applicationProperties
     * @param lastUpdateFrom
     * @param lastUpdateUntil
     * @param pgbl
     * @param response
     * @param uriBuilder
     * @return
     */
    public static Page<DataResource> readAllResources(RepoBaseConfiguration applicationProperties,
            Instant lastUpdateFrom,
            Instant lastUpdateUntil,
            Pageable pgbl,
            final HttpServletResponse response,
            final UriComponentsBuilder uriBuilder) {
        return readAllResourcesFilteredByExample(applicationProperties, null, lastUpdateFrom, lastUpdateUntil, pgbl, response, uriBuilder);
    }

    /**
     * Read all existing resources found by example.
     *
     * @param applicationProperties
     * @param example
     * @param lastUpdateFrom
     * @param lastUpdateUntil
     * @param pgbl
     * @param response
     * @param uriBuilder
     * @return
     */
    public static Page<DataResource> readAllResourcesFilteredByExample(RepoBaseConfiguration applicationProperties,
            DataResource example,
            Instant lastUpdateFrom,
            Instant lastUpdateUntil,
            Pageable pgbl,
            final HttpServletResponse response,
            final UriComponentsBuilder uriBuilder) {
        PageRequest request = ControllerUtils.checkPaginationInformation(pgbl);
        Page<DataResource> page = applicationProperties.getDataResourceService().findByExample(example, lastUpdateFrom, lastUpdateUntil, AuthenticationHelper.getAuthorizationIdentities(),
                AuthenticationHelper.hasAuthority(RepoUserRole.ADMINISTRATOR.toString()),
                request);
        if (example != null) {
            //comming via dataresources/search
            applicationProperties.getEventPublisher().publishEvent(new PaginatedResultsRetrievedEvent<>(DataResource.class, "search", uriBuilder, response, page.getNumber(), page.getTotalPages(), request.getPageSize()));
        } else {
            //comming via dataresources/ ... don't add search suffix
            applicationProperties.getEventPublisher().publishEvent(new PaginatedResultsRetrievedEvent<>(DataResource.class, uriBuilder, response, page.getNumber(), page.getTotalPages(), request.getPageSize()));
        }
        return page;
    }

    /**
     * Updata an existing resource.
     *
     * @param applicationProperties
     * @param identifier
     * @param newResource
     * @param request
     * @param supplier
     * @return
     */
    public static DataResource updateResource(RepoBaseConfiguration applicationProperties,
            String identifier,
            final DataResource newResource,
            final WebRequest request,
            Function<String, String> supplier) {
        String eTag = ControllerUtils.getEtagFromHeader(request);
        return updateResource(applicationProperties, identifier, newResource, eTag, supplier);
    }

    /**
     * Updata an existing resource.
     *
     * @param applicationProperties Configuration holding all services.
     * @param identifier Identifier of the resource
     * @param newResource New resource
     * @param eTag Expected E-Tag of the 'old' resource
     * @param supplier Method for determining URL in case of an error.
     * @return
     */
    public static DataResource updateResource(RepoBaseConfiguration applicationProperties,
            String identifier,
            final DataResource newResource,
            final String eTag,
            Function<String, String> supplier) {
        if (applicationProperties.isReadOnly()) {
            String message = "Repository is in read-only mode. Put request denied.";
            LOGGER.info(message);
            throw new ServiceUnavailableException(message);
        }

        ControllerUtils.checkAnonymousAccess();
        DataResource resource = getResourceByIdentifierOrRedirect(applicationProperties, identifier, null, supplier);
        DataResourceUtils.performPermissionCheck(resource, PERMISSION.WRITE);

        ControllerUtils.checkEtag(eTag, resource);
        newResource.setId(resource.getId());

        DataResource result = applicationProperties.getDataResourceService().put(resource, newResource, getUserAuthorities(resource));
        return result;
    }

    /**
     * Patch an existing data resource.
     *
     * @param applicationProperties
     * @param identifier
     * @param patch
     * @param eTag
     * @param patchContentInformation
     */
    public static void patchResource(RepoBaseConfiguration applicationProperties,
            String identifier,
            JsonPatch patch,
            String eTag,
            Function<String, String> patchContentInformation) {
        if (applicationProperties.isReadOnly()) {
            String message = "Repository is in read-only mode. Patch request denied.";
            LOGGER.info(message);
            throw new ServiceUnavailableException(message);
        }

        ControllerUtils.checkAnonymousAccess();

        DataResource resource = DataResourceUtils.getResourceByIdentifierOrRedirect(applicationProperties, identifier, null, patchContentInformation);

        DataResourceUtils.performPermissionCheck(resource, PERMISSION.WRITE);

        ControllerUtils.checkEtag(eTag, resource);

        applicationProperties.getDataResourceService().patch(resource, patch, getUserAuthorities(resource));
        return;
    }

    /**
     * Delete an existing resource.
     *
     * @param applicationProperties
     * @param identifier
     * @param request
     * @param supplier
     */
    public static void deleteResource(RepoBaseConfiguration applicationProperties,
            String identifier,
            final WebRequest request,
            Function<String, String> supplier) {
        String etagFromHeader = ControllerUtils.getEtagFromHeader(request);
        deleteResource(applicationProperties, identifier, etagFromHeader, supplier);
    }

    /**
     * Delete an existing resource.
     *
     * @param applicationProperties
     * @param identifier
     * @param eTag
     * @param supplier
     */
    public static void deleteResource(RepoBaseConfiguration applicationProperties,
            String identifier,
            final String eTag,
            Function<String, String> supplier) {
        if (applicationProperties.isReadOnly()) {
            String message = "Repository is in read-only mode. Delete request denied.";
            LOGGER.info(message);
            throw new ServiceUnavailableException(message);
        }

        ControllerUtils.checkAnonymousAccess();

        try {
            DataResource resource = getResourceByIdentifierOrRedirect(applicationProperties, identifier, null, supplier);
            LOGGER.trace("Resource found. Checking for permission {} or role {}.", PERMISSION.ADMINISTRATE, RepoUserRole.ADMINISTRATOR);
            if (DataResourceUtils.hasPermission(resource, PERMISSION.ADMINISTRATE) || 
                    AuthenticationHelper.hasAuthority(RepoUserRole.ADMINISTRATOR.getValue())) {
                LOGGER.trace("Permissions found. Continuing with DELETE operation.");
                ControllerUtils.checkEtag(eTag, resource);
                if (!DataResource.State.REVOKED.equals(resource.getState()) ||
                        AuthenticationHelper.hasAuthority(RepoUserRole.ADMINISTRATOR.getValue()) || 
                        AuthenticationHelper.isPrincipal("SELF")) {
                    //call delete if resource not revoked (to revoke it) or if it is revoked and role is administrator or caller is repository itself (to set state to GONE)
                    applicationProperties.getDataResourceService().delete(resource);
                }
            } else {
                String message = "Insufficient permissions. ADMINISTRATE permission or ROLE_ADMINISTRATOR required.";
                LOGGER.info(message);
                throw new UpdateForbiddenException(message);
            }
        } catch (ResourceNotFoundException ex) {
            //ignored
            LOGGER.info("Resource with identifier {} not found. Returning with HTTP NO_CONTENT.", identifier);
        }

    }

    /**
     * Restore a previously deleted resource.
     *
     * @param applicationProperties
     * @param identifier
     * @param request
     * @param supplier
     */
    public static DataResource restoreResource(RepoBaseConfiguration applicationProperties,
            String identifier,
            final WebRequest request,
            Function<String, String> supplier) {
        String etagFromHeader = ControllerUtils.getEtagFromHeader(request);
        return restoreResource(applicationProperties, identifier, etagFromHeader, supplier);
    }

    /**
     * Restore a previously deleted resource.
     *
     * @param applicationProperties
     * @param identifier
     * @param eTag
     * @param supplier
     * @return Restored resource.
     */
    public static DataResource restoreResource(RepoBaseConfiguration applicationProperties,
            String identifier,
            final String eTag,
            Function<String, String> supplier) {
      DataResource restoredResource = null;
      
        if (applicationProperties.isReadOnly()) {
            String message = "Repository is in read-only mode. Restore request denied.";
            LOGGER.info(message);
            throw new ServiceUnavailableException(message);
        }

        ControllerUtils.checkAnonymousAccess();

        try {
            DataResource resource = getResourceByIdentifierOrRedirect(applicationProperties, identifier, null, supplier);
            LOGGER.trace("Resource found. Checking for permission {} or role {}.", PERMISSION.ADMINISTRATE, RepoUserRole.ADMINISTRATOR);
            if (DataResourceUtils.hasPermission(resource, PERMISSION.ADMINISTRATE) || 
                    AuthenticationHelper.hasAuthority(RepoUserRole.ADMINISTRATOR.getValue())) {
                LOGGER.trace("Permissions found. Continuing with RESTORE operation.");
                ControllerUtils.checkEtag(eTag, resource);
                if (!DataResource.State.GONE.equals(resource.getState()) ||
                        AuthenticationHelper.hasAuthority(RepoUserRole.ADMINISTRATOR.getValue()) || 
                        AuthenticationHelper.isPrincipal("SELF")) {
                    //call restore if resource not gone (to make it visible again) or if it is gone and role is administrator or caller is repository itself (to set state to REVOKED)
                    restoredResource = applicationProperties.getDataResourceService().restore(resource);
                }
            } else {
                String message = "Insufficient permissions. ADMINISTRATE permission or ROLE_ADMINISTRATOR required.";
                LOGGER.info(message);
                throw new UpdateForbiddenException(message);
            }
        } catch (ResourceNotFoundException ex) {
            //ignored
            LOGGER.info("Resource with identifier {} not found. Returning with HTTP NO_CONTENT.", identifier);
        }
      return restoredResource;
    }

    public static String getInternalIdentifier(DataResource resource) {
        for (Identifier alt : resource.getAlternateIdentifiers()) {
            if (Identifier.IDENTIFIER_TYPE.INTERNAL.equals(alt.getIdentifierType())) {
                return alt.getValue();
            }
        }
        return null;
    }

    /**
     *
     * @param applicationProperties
     * @param resourceIdentifier
     * @param pgbl
     * @param supplier
     * @return
     */
    public static Optional<String> getAuditInformation(RepoBaseConfiguration applicationProperties,
            final String resourceIdentifier,
            final Pageable pgbl,
            Function<String, String> supplier) {

        DataResource resource = DataResourceUtils.getResourceByIdentifierOrRedirect(applicationProperties, resourceIdentifier, null, supplier);
        DataResourceUtils.performPermissionCheck(resource, PERMISSION.READ);

        return applicationProperties.getDataResourceService().getAuditInformationAsJson(resourceIdentifier, pgbl);
    }

    /**
     * Remove ACLs from data resource.
     *
     * @param resource data resource.
     * @return data resource without acls.
     */
    public static DataResource filterResource(DataResource resource) {
        if (!AuthenticationHelper.isAuthenticatedAsService() && !DataResourceUtils.hasPermission(resource, PERMISSION.ADMINISTRATE) && !AuthenticationHelper.hasAuthority(RepoUserRole.ADMINISTRATOR.toString())) {
            LOGGER.debug("Removing ACL information from resources due to non-administrator access.");
            //exclude ACLs if not administrate or administrator permissions are set
            resource.setAcls(null);
        } else {
            LOGGER.debug("Administrator access detected, keeping ACL information in resources.");
        }

        return resource;
    }

    /**
     * Remove ACLs from a list of data resources.
     *
     * @param resources list of data resources.
     * @return list of data resources without acls.
     */
    public static List<DataResource> filterResources(List<DataResource> resources) {

        if (!AuthenticationHelper.isAuthenticatedAsService() && !AuthenticationHelper.hasAuthority(RepoUserRole.ADMINISTRATOR.toString())) {
            LOGGER.debug("Removing ACL information from resources due to non-administrator access.");
            //exclude ACLs if not administrate or administrator permissions are set
            resources.forEach((resource) -> {
                //resource.setAcls(null);
                filterResource(resource);
            });
        } else {
            LOGGER.debug("Administrator access detected, keeping ACL information in resources.");
        }

        return resources;
    }

    public static Collection<? extends GrantedAuthority> getUserAuthorities(DataResource resource) {
        LOGGER.trace("Determining user grants from authorization context.");
        Collection<GrantedAuthority> userGrants = new ArrayList<>();
        userGrants.add(new SimpleGrantedAuthority(DataResourceUtils.getAccessPermission(resource).getValue()));

        if (AuthenticationHelper.hasAuthority(RepoUserRole.ADMINISTRATOR.toString())) {
            LOGGER.trace("Administrator access detected. Adding role {} to granted authorities.", RepoUserRole.ADMINISTRATOR.getValue());
            userGrants.add(new SimpleGrantedAuthority(RepoUserRole.ADMINISTRATOR.getValue()));
        }

        return userGrants;
    }

    /**
     * Check for sufficient permissions to access the provided resource with the
     * provided required permission. Permission evaluation is done in three
     * steps:
     *
     * At first, access permissions are obtained via {@link #getAccessPermission(edu.kit.datamanager.repo.domain.DataResource)
     * }.
     *
     * The second step depends on the state of the resource. If the resource is
     * FIXED and WRITE permissions are requested, the caller permission must be
     * ADMINISTRATE, which is the case for the owner and administrators.
     * Otherwise, write access is forbidden. The same applies if the resource if
     * REVOKED. In that case, for all access types (READ, WRITE, ADMINISTRATE)
     * the caller must have ADMINISTRATE permissions.
     *
     * In a final step it is checked, if the caller permission is matching at
     * least the requested permission. If this is the case, this method will
     * return silently.
     *
     * In all other cases where requirements are not met, an
     * AccessForbiddenException or ResourceNotFoundException will be thrown.
     *
     *
     * @param resource The resource to check.
     * @param requiredPermission The required permission to access the resource.
     *
     * @throws AccessForbiddenException if the caller has not the required
     * permissions.
     * @throws ResourceNotFoundException if the resource has been revoked and
     * the caller has no ADMINISTRATE permissions.
     */
    public static void performPermissionCheck(DataResource resource, PERMISSION requiredPermission) throws AccessForbiddenException, ResourceNotFoundException {
        LOGGER.debug("Performing permission check for resource {} and permission {}.", "DataResource#" + resource.getId(), requiredPermission);
        PERMISSION callerPermission = getAccessPermission(resource);

        LOGGER.debug("Obtained caller permission {}. Checking resource state for special handling.", callerPermission);
        if (resource.getState() != null) {
            switch (resource.getState()) {
                case FIXED:
                    LOGGER.debug("Performing special access check for FIXED resource and {} permission.", requiredPermission);
                    //resource is fixed, only check if WRITE permissions are required
                    if (requiredPermission.atLeast(PERMISSION.WRITE) && !callerPermission.atLeast(PERMISSION.ADMINISTRATE)) {
                        //no access, return 403 as resource has been revoked
                        LOGGER.debug("{} permission to fixed resource NOT granted to principal with identifiers {}. ADMINISTRATE permissions required.", requiredPermission, AuthenticationHelper.getAuthorizationIdentities());
                        throw new AccessForbiddenException("Resource has been fixed. Modifications to this resource are no longer permitted.");
                    }
                    break;
                case REVOKED:
                    LOGGER.debug("Performing special access check for REVOKED resource and {} permission.", requiredPermission);
                    //resource is revoked, check ADMINISTRATE or ADMINISTRATOR permissions
                    if (!callerPermission.atLeast(PERMISSION.ADMINISTRATE)) {
                        //no access, return 404 as resource has been revoked
                        LOGGER.debug("Access to revoked resource NOT granted to principal with identifiers {}. ADMINISTRATE permissions required.", requiredPermission, AuthenticationHelper.getAuthorizationIdentities());
                        throw new ResourceNotFoundException("The resource never was or is not longer available.");
                    }
                    break;
                case VOLATILE:
                    LOGGER.trace("Resource state is {}. No special access check necessary.", resource.getState());
                    break;
                case GONE:
                    String message = "The resource never was or is not longer available.";
                    LOGGER.info(message);
                    throw new ResourceNotFoundException(message);
                default:
                    LOGGER.warn("Unhandled resource state {} detected. Not applying any special access checks.", resource.getState());
            }
        }

        LOGGER.debug("Checking if caller permission {} meets required permission {}.", callerPermission, requiredPermission);
        if (!callerPermission.atLeast(requiredPermission)) {
            LOGGER.debug("Caller permission {} does not met required permission {}. Resource access NOT granted.", requiredPermission);
            throw new AccessForbiddenException("Resource access restricted by acl.");
        } else {
            LOGGER.debug("{} permission to resource granted to principal with identifiers {}.", requiredPermission, AuthenticationHelper.getAuthorizationIdentities());
        }

    }

    /**
     * Determine the maximum permission for the resource being accessed using
     * the internal authorization object. This method will go through all
     * permissions and principals to determine the maximum permission.
     *
     * @param resource The resource for which the permission should be
     * determined.
     *
     * @return The maximum permission. PERMISSION.NONE is returned if no
     * permission was found.
     */
    public static PERMISSION getAccessPermission(DataResource resource) {
        //quick check for admin permission
        if (AuthenticationHelper.hasAuthority(RepoUserRole.ADMINISTRATOR.getValue())) {
            return PERMISSION.ADMINISTRATE;
        }

        //check for service roles
        PERMISSION servicePermission = PERMISSION.NONE;
        if (AuthenticationHelper.hasAuthority(RepoServiceRole.SERVICE_ADMINISTRATOR.getValue())) {
            servicePermission = PERMISSION.ADMINISTRATE;
        } else if (AuthenticationHelper.hasAuthority(RepoServiceRole.SERVICE_WRITE.getValue())) {
            servicePermission = PERMISSION.WRITE;
        } else if (AuthenticationHelper.hasAuthority(RepoServiceRole.SERVICE_READ.getValue())) {
            servicePermission = PERMISSION.READ;
        }

        
        //quick check for temporary roles
        edu.kit.datamanager.entities.PERMISSION permission = AuthenticationHelper.getScopedPermission(DataResource.class.getSimpleName(), resource.getId());
        if (permission.atLeast(edu.kit.datamanager.entities.PERMISSION.READ)) {
            return permission;
        }

        List<String> principalIds = AuthenticationHelper.getAuthorizationIdentities();
        PERMISSION maxPermission = PERMISSION.NONE;
        for (AclEntry entry : resource.getAcls()) {
            if (AclUtils.isSidInPrincipalList(entry.getSid(), principalIds) && entry.getPermission().ordinal() > maxPermission.ordinal()) {
                maxPermission = entry.getPermission();
            }
        }

        //return service permission if higher, otherwise return maxPermission
        if (servicePermission.atLeast(maxPermission)) {
            return servicePermission;
        }

        return maxPermission;
    }

    /**
     * Check if the internal authorization object has the provided permission.
     * This method will stop after a principal was found posessing the provided
     * permission.
     *
     * @param resource The resource for which the permission should be
     * determined.
     * @param permission The permission to check for.
     *
     * @return TRUE if any sid has the requested permission or if the caller has
     * administrator permissions.
     */
    public static boolean hasPermission(DataResource resource, PERMISSION permission) {
        return getAccessPermission(resource).atLeast(permission);
    }

    /**
     * Test if two acl lists are identical. This method returns FALSE if both
     * arrays have a different length or if at least one entry is different,
     * e.g. does not exist of has a different permission. Otherwise, TRUE is
     * returned. The implemented check is independent from the order of both
     * arrays.
     *
     * @param first The first acl array, which is the reference.
     * @param second The second acl array.
     *
     * @return TRUE if all array elements of 'first' exist in 'second' with the
     * same PERMISSION, FALSE otherwise.
     */
    public static boolean areAclsEqual(@NonNull AclEntry[] first, @NonNull AclEntry[] second) {

        if (first.length != second.length) {
            //size differs, lists cannot be identical
            return false;
        }
        Map<String, PERMISSION> aclMapBefore = aclEntriesToMap(first);
        Map<String, PERMISSION> aclMapAfter = aclEntriesToMap(second);

        return aclMapBefore.entrySet().stream().noneMatch((entry) -> (!entry.getValue().equals(aclMapAfter.get(entry.getKey()))));
    }

    /**
     * Helper to create a map from an acl array.
     */
    private static Map<String, PERMISSION> aclEntriesToMap(AclEntry... entries) {
        Map<String, PERMISSION> aclMap = new HashMap<>();
        for (AclEntry entry : entries) {
            aclMap.put(entry.getSid(), entry.getPermission());
        }
        return aclMap;
    }

    /**
     * Helper methods for internal use.*
     *
     * @param applicationProperties
     * @param identifier
     * @param version
     * @param supplier
     * @return
     */
    public static DataResource getResourceByIdentifierOrRedirect(RepoBaseConfiguration applicationProperties,
            String identifier,
            Long version,
            Function<String, String> supplier) {
        String decodedIdentifier;
        try {
            LOGGER.trace("Performing getResourceByIdentifierOrRedirect({}, {}, #Function).", identifier, version);
            decodedIdentifier = URLDecoder.decode(identifier, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            LOGGER.error("Failed to decode resource identifier " + identifier + ".", ex);
            throw new CustomInternalServerError("Failed to decode provided identifier " + identifier + ".");
        }
        LOGGER.trace("Decoded resource identifier: {}", decodedIdentifier);
        DataResource resource = applicationProperties.getDataResourceService().findByAnyIdentifier(decodedIdentifier, version);

        //check if resource was found by resource identifier 
        if (!Objects.equals(decodedIdentifier, resource.getId())) {
            //resource was found by another identifier...redirect
            String encodedIdentifier;
            try {
                encodedIdentifier = URLEncoder.encode(resource.getId(), "UTF-8");
            } catch (UnsupportedEncodingException ex) {
                LOGGER.error("Failed to encode resource identifier " + resource.getId() + ".", ex);
                throw new CustomInternalServerError("Failed to encode resource identifier " + resource.getId() + ".");
            }
            LOGGER.trace("No resource for identifier {} found. Redirecting to resource with identifier {}.", identifier, encodedIdentifier);
            String redirectResourceTo = (supplier != null) ? supplier.apply(encodedIdentifier) : encodedIdentifier;
            throw new ResourceElsewhereException(redirectResourceTo);
        }
        //resource was found by resource identifier...return and proceed
        LOGGER.trace("Resource for identifier {} found. Returning resource #{}.", decodedIdentifier, resource.getId());
        return resource;
    }

    /**
     * Make a copy of the resource to avoid updating database.
     *
     * @param dataresource resource linked with the database.
     * @return unlinked data resource.
     */
    public static DataResource copyDataResource(DataResource dataresource) {
        DataResource returnValue;
        try {
            String jsonString = mapper.writeValueAsString(dataresource);
            LOGGER.trace("dataresource: {}", jsonString);
            returnValue = mapper.readValue(jsonString, DataResource.class);
        } catch (JsonProcessingException ex) {
            LOGGER.error("Error mapping dataresource!");
            returnValue = dataresource;
        }
        return returnValue;
    }

    /**
     * Make a copy of the resource to avoid updating database.
     *
     * @param dataresource resource linked with the database.
     * @return unlinked data resource.
     */
    public static edu.kit.datamanager.entities.repo.DataResource migrateToDataResource(DataResource dataresource) {
        edu.kit.datamanager.entities.repo.DataResource returnValue = null;
        try {
            String jsonString = mapper.writeValueAsString(dataresource);
            LOGGER.trace("dataresource: {}", jsonString);
            returnValue = mapper.readValue(jsonString, edu.kit.datamanager.entities.repo.DataResource.class);
        } catch (JsonProcessingException ex) {
            LOGGER.error("Error mapping dataresource!");
        }
        return returnValue;
    }

}
