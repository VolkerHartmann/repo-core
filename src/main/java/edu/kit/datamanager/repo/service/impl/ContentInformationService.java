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

import edu.kit.datamanager.entities.ContentElement;
import edu.kit.datamanager.entities.PERMISSION;
import edu.kit.datamanager.entities.RepoUserRole;
import edu.kit.datamanager.entities.messaging.DataResourceMessage;
import edu.kit.datamanager.exceptions.BadArgumentException;
import edu.kit.datamanager.exceptions.CustomInternalServerError;
import edu.kit.datamanager.exceptions.FeatureNotImplementedException;
import edu.kit.datamanager.exceptions.ResourceNotFoundException;
import edu.kit.datamanager.exceptions.UpdateForbiddenException;
import edu.kit.datamanager.repo.configuration.RepoBaseConfiguration;
import edu.kit.datamanager.repo.dao.spec.contentinformation.ContentInformationContentUriSpecification;
import edu.kit.datamanager.repo.dao.spec.contentinformation.ContentInformationMediaTypeSpecification;
import edu.kit.datamanager.repo.dao.spec.contentinformation.ContentInformationMatchSpecification;
import edu.kit.datamanager.repo.dao.spec.contentinformation.ContentInformationMetadataSpecification;
import edu.kit.datamanager.repo.dao.spec.contentinformation.ContentInformationPermissionSpecification;
import edu.kit.datamanager.repo.dao.spec.contentinformation.ContentInformationRelativePathSpecification;
import edu.kit.datamanager.repo.dao.spec.contentinformation.ContentInformationTagSpecification;
import edu.kit.datamanager.repo.dao.IContentInformationDao;
import edu.kit.datamanager.repo.domain.ContentInformation;
import edu.kit.datamanager.repo.domain.DataResource;
import edu.kit.datamanager.repo.service.IContentInformationService;
import edu.kit.datamanager.repo.service.IRepoVersioningService;
import edu.kit.datamanager.service.IContentCollectionProvider;
import edu.kit.datamanager.service.IContentProvider;
import edu.kit.datamanager.service.IMessagingService;
import edu.kit.datamanager.service.impl.LogfileMessagingService;
import edu.kit.datamanager.util.AuthenticationHelper;
import edu.kit.datamanager.util.ControllerUtils;
import edu.kit.datamanager.util.PatchUtil;
import edu.kit.datamanager.util.json.JsonPatch;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 *
 * @author jejkal
 */
public class ContentInformationService implements IContentInformationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContentInformationService.class);

    @Autowired
    private IContentInformationDao dao;

    private RepoBaseConfiguration applicationProperties;

    /**
     * Optional messagingService bean may or may not be available, depending on
     * a service's configuration. If messaging capabilities are disabled, this
     * bean should be not available. In that case, messages are only logged.
     */
    @Autowired
    private Optional<IMessagingService> messagingService;

    @Autowired
    private IRepoVersioningService[] versioningServices;

    @Autowired
    private IContentProvider[] contentProviders;

    @Autowired
    private IContentCollectionProvider[] collectionContentProviders;

    /**
     * Default constructor.
     */
    public ContentInformationService() {
    }

    @Override
    public void configure(RepoBaseConfiguration applicationProperties) {
        this.applicationProperties = applicationProperties;
    }

    @Override
    @Transactional
    public ContentInformation create(ContentInformation contentInformation, DataResource resource,
            String path,
            InputStream file,
            boolean force) {
        LOGGER.trace("Performing create({}, {}, {}, {}, {}).", contentInformation, "DataResource#" + resource.getId(), "<InputStream>", path, force);

        Optional<ContentInformation> existingContentInformation = dao.findByParentResourceAndRelativePath(resource, path);
        Map<String, String> options = new HashMap<>();
        options.put("force", Boolean.toString(force));

        ContentInformation contentInfo;
        String newFileVersion = "1";
        if (existingContentInformation.isPresent()) {
            contentInfo = existingContentInformation.get();
            options.put("contentUri", contentInfo.getContentUri());
        } else {
            LOGGER.trace("No existing content information found.");
            //no existing content information, create new or take provided
            contentInfo = (contentInformation != null) ? contentInformation : ContentInformation.createContentInformation(path);
            contentInfo.setId(null);
            contentInfo.setParentResource(resource);
            contentInfo.setRelativePath(path);
            contentInfo.setMediaType((contentInformation != null) ? contentInformation.getMediaType() : null);
        }

        if (contentInfo.getMediaType() != null) {
            LOGGER.trace("Using provided/existing media type {}.", contentInfo.getMediaType());
            options.put("mediaType", contentInfo.getMediaType());
        }

        if (file != null) {
            LOGGER.trace("User upload detected. Preparing to consume data.");
            //file upload

            String versioningService = (contentInformation != null && contentInformation.getVersioningService() != null) ? contentInformation.getVersioningService() : applicationProperties.getVersioningService().getServiceName();
            contentInfo.setVersioningService(versioningService);
            boolean fileWritten = false;
            LOGGER.trace("Trying to use versioning service named '{}' for writing file content.", versioningService);
            for (IRepoVersioningService service : versioningServices) {
                if (versioningService.equals(service.getServiceName())) {
                    LOGGER.trace("Versioning service found, writing file content.");
                    service.configure(applicationProperties);
                    try {
                        service.write(resource.getId(), AuthenticationHelper.getPrincipal(), path, file, options);
                    } catch (Throwable t) {
                        LOGGER.error("Failed to write content using versioning service " + versioningService + ".", t);
                        throw t;
                    }
                    LOGGER.trace("File content successfully written.");
                    fileWritten = true;
                } else {
                    LOGGER.trace("Skipping service '{}'", service.getServiceName());
                }
            }

            if (!fileWritten) {
                LOGGER.error("No versioning service found for name '{}'.", versioningService);
                throw new BadArgumentException("Versioning service '" + versioningService + "' not found.");
            }

            LOGGER.trace("Obtaining file-specific information from versioning service response.");
            if (options.containsKey("size")) {
                contentInfo.setSize(Long.parseLong(options.get("size")));
            }

            if (options.containsKey("checksum")) {
                contentInfo.setHash(options.get("checksum"));
            }
            if (options.containsKey("contentUri")) {
                contentInfo.setContentUri(options.get("contentUri"));
            }
            if (options.containsKey("mediaType")) {
                contentInfo.setMediaType(options.get("mediaType"));
            }

            if (options.containsKey("fileVersion")) {
                //use file version from versioning service if provided
                newFileVersion = options.get("fileVersion");
                LOGGER.trace("Using new file version '{}' from versioning service. ", newFileVersion);
            } else {
                LOGGER.trace("Using default file version '{}'. ", newFileVersion);
            }

            contentInfo.setFileVersion(newFileVersion);

            LOGGER.trace("File successfully written using versioning service '{}'.", versioningService);
        } else {
            LOGGER.trace("No user upload detected. Checking content URI in content information.");
            //no file upload, take data reference URI from provided content information
            if (contentInformation == null || contentInformation.getContentUri() == null) {
                LOGGER.error("No content URI provided in content information. Throwing BadArgumentException.");
                throw new BadArgumentException("Neither a file upload nor an external content URI were provided.");
            } else {
                LOGGER.trace("Content URI {} detected. Checking URI scheme.", contentInfo.getContentUri());
                if ("file".equals(URI.create(contentInfo.getContentUri()).getScheme().toLowerCase()) && !AuthenticationHelper.hasAuthority(RepoUserRole.ADMINISTRATOR.getValue())) {
                    LOGGER.error("Content URI scheme is 'file' but caller has no ADMINISTRATOR role. Content information creation rejected. Throwing BadArgumentException.");
                    throw new BadArgumentException("You are not permitted to add content information with URI scheme of type 'file'.");
                }
                LOGGER.trace("Accepting attributed from provided content information.");
                //take content uri and provided checksum and size, if available
                contentInfo.setContentUri(contentInformation.getContentUri());
                LOGGER.debug("Assigned content URI {} to content information.", contentInfo.getContentUri());
                contentInfo.setSize(contentInformation.getSize());
                LOGGER.debug("Assigned size {} to content information.", contentInfo.getSize());
                contentInfo.setHash(contentInformation.getHash());
                LOGGER.debug("Assigned hash {} to content information.", contentInfo.getHash());
                contentInfo.setFileVersion(newFileVersion);
                LOGGER.trace("Using default file version {}.", newFileVersion);
                contentInfo.setFileVersion(newFileVersion);
            }
        }

        //copy metadata and tags from provided content information if available
        LOGGER.trace("Checking for additional metadata.");
        if (contentInformation != null) {
            if (contentInformation.getMetadata() != null) {
                LOGGER.trace("Additional metadata found. Transferring value.");
                contentInfo.setMetadata(contentInformation.getMetadata());
            }

            if (contentInformation.getTags() != null) {
                LOGGER.trace("User-provided tags found. Transferring value.");
                contentInfo.setTags(contentInformation.getTags());
            }
            if (contentInformation.getUploader() != null) {
                LOGGER.trace("User-provided uploader found. Transferring value.");
                contentInfo.setUploader(contentInformation.getUploader());
            }
        } else {
            String principal = AuthenticationHelper.getPrincipal();
            LOGGER.trace("No content information provided. Setting uploader property from caller principal value {}.", principal);
            contentInfo.setUploader(principal);
        }

        long newMetadataVersion = (contentInfo.getId() != null) ? applicationProperties.getContentInformationAuditService().getCurrentVersion(Long.toString(contentInfo.getId())) + 1 : 1;
        LOGGER.trace("Setting new version number of content information to {}.", newMetadataVersion);
        contentInfo.setVersion((int) newMetadataVersion);

        LOGGER.trace("Persisting content information.");
        ContentInformation result = getDao().save(contentInfo);

        LOGGER.trace("Capturing audit information.");
        applicationProperties.getContentInformationAuditService().captureAuditInformation(result, AuthenticationHelper.getPrincipal());

        LOGGER.trace("Sending CREATE event.");
        messagingService.orElse(new LogfileMessagingService()).send(DataResourceMessage.factoryCreateDataMessage(resource.getId(), result.getRelativePath(), result.getContentUri(), result.getMediaType(), AuthenticationHelper.getPrincipal(), ControllerUtils.getLocalHostname()));
        return result;
    }

    @Override
    public void read(DataResource resource, String path, Long version, String acceptHeader, HttpServletResponse response) {
        URI uri;
        if (path.endsWith("/") || path.isEmpty()) {
            //collection download
            Page<ContentInformation> page = dao.findByParentResource(resource, PageRequest.of(0, Integer.MAX_VALUE));
            if (page.isEmpty()) {
                //nothing to provide
                String message = "No content found at the provided location.";
                LOGGER.debug(message);
                throw new ResourceNotFoundException(message);
            }

            MediaType acceptHeaderType = acceptHeader != null ? MediaType.parseMediaType(acceptHeader) : null;
            boolean provided = false;
            Set<MediaType> acceptableMediaTypes = new HashSet<>();
            for (IContentCollectionProvider provider : collectionContentProviders) {
                if (acceptHeaderType != null && provider.supportsMediaType(acceptHeaderType)) {
                    List<ContentElement> elements = new ArrayList<>();
                    page.getContent().forEach((c) -> {
                        URI contentUri = URI.create(c.getContentUri());
                        if (provider.canProvide(contentUri.getScheme())) {
                            String contextUri = ServletUriComponentsBuilder.fromCurrentRequest().toUriString();
                            LOGGER.trace("Adding collection mapping '{}':'{}' with checksum '{}' to list. Additionally providing context Uri {} and size {}.", c.getRelativePath(), contentUri, c.getHash(), contextUri, c.getSize());
                            elements.add(ContentElement.createContentElement(resource.getId(), c.getRelativePath(), c.getContentUri(), c.getFileVersion(), c.getVersioningService(), c.getHash(), contextUri, c.getSize()));
                        } else {
                            LOGGER.debug("Skip adding collection mapping '{}':'{}' to map as content provider {} is not capable of providing URI scheme.", c.getRelativePath(), contentUri, provider.getClass());
                        }
                    });
                    LOGGER.trace("Start providing content.");
                    provider.provide(elements, MediaType.parseMediaType(acceptHeader), response);
                    LOGGER.trace("Content successfully provided.");
                    provided = true;
                } else {
                    Collection<MediaType> col = new ArrayList<>();
                    Collections.addAll(col, provider.getSupportedMediaTypes());
                    acceptableMediaTypes.addAll(col);
                }
                break;
            }

            if (!provided) {
                //we are done here, content is already submitted
                LOGGER.info("No content collection provider found for media type {} in Accept header. Throwing HTTP 415 (UNSUPPORTED_MEDIA_TYPE).", acceptHeaderType);
                throw new UnsupportedMediaTypeStatusException(acceptHeaderType, new ArrayList<>(acceptableMediaTypes));
            }
        } else {
            //try to obtain single content element matching path exactly
            ContentInformation contentInformation = getContentInformation(resource.getId(), path, version);
            uri = (contentInformation.getContentUri() != null) ? URI.create(contentInformation.getContentUri()) : null;
            String contentScheme = (uri != null) ? uri.getScheme() : "file";
            LOGGER.debug("Trying to provide content at URI {} by any configured content provider.", uri);
            boolean provided = false;
            for (IContentProvider contentProvider : contentProviders) {
                if (contentProvider.canProvide(contentScheme)) {
                    LOGGER.trace("Using content provider {}.", contentProvider.getClass());
                    String contextUri = ServletUriComponentsBuilder.fromCurrentRequest().toUriString();
                    contentProvider.provide(ContentElement.createContentElement(resource.getId(),
                            contentInformation.getRelativePath(), contentInformation.getContentUri(),
                            contentInformation.getFileVersion(),
                            contentInformation.getVersioningService(),
                            contentInformation.getHash(),
                            contextUri,
                            contentInformation.getSize()),
                            contentInformation.getMediaTypeAsObject(),
                            contentInformation.getFilename(),
                            response);
                    provided = true;
                    break;
                }
            }
            if (!provided) {
                //obtain data uri and check for content to exist
                String dataUri = contentInformation.getContentUri();
                if (dataUri != null) {
                    uri = URI.create(dataUri);
                    LOGGER.info("No content provider found for URI {}. Returning URI in Content-Location header.", uri);
                    HttpHeaders headers = new HttpHeaders();
                    headers.add("Content-Location", uri.toString());
                    Set<String> headerKeys = headers.keySet();
                    headerKeys.forEach((headerKey) -> {
                        headers.get(headerKey).forEach((value) -> {
                            response.addHeader(headerKey, value);
                        });
                    });
                    response.setStatus(HttpStatus.NO_CONTENT.value());
                } else {
                    LOGGER.info("No data URI found for resource with identifier {} and path {}. Returning HTTP 404.", resource.getId(), path);
                    throw new ResourceNotFoundException("No data URI found for the addressed content.");
                }
            }
        }
    }

    @Override
    public ContentInformation getContentInformation(String identifier, String relativePath, Long version) {
        LOGGER.trace("Performing getContentInformation({}, {}).", identifier, relativePath);

        LOGGER.trace("Performing findOne({}, {}).", identifier, relativePath);
        Specification<ContentInformation> spec = Specification.where(ContentInformationMatchSpecification.toSpecification(identifier, relativePath, true));

        Optional<ContentInformation> contentInformation = dao.findOne(spec);

        if (!contentInformation.isPresent()) {
            //TODO: check later for collection download
            LOGGER.error("No content found for resource {} at path {}. Throwing ResourceNotFoundException.", identifier, relativePath);
            throw new ResourceNotFoundException("No content information for identifier " + identifier + ", path " + relativePath + " found.");
        }
        ContentInformation result = contentInformation.get();

        if (applicationProperties.isAuditEnabled() && Objects.nonNull(version)) {
            LOGGER.trace("Obtained content information for identifier {}. Checking for shadow of version {}.", result.getId(), version);
            Optional<ContentInformation> optAuditResult = applicationProperties.getContentInformationAuditService().getResourceByVersion(Long.toString(result.getId()), version);
            if (optAuditResult.isPresent()) {
                LOGGER.trace("Shadow successfully obtained. Returning version {} of content information with id {}.", version, result.getId());
                result = optAuditResult.get();
            } else {
                LOGGER.info("Version {} of content information {} not found. Returning HTTP 404 (NOT_FOUND).", version, result.getId());
                throw new ResourceNotFoundException("Content information with identifier " + result.getId() + " is not available in version " + version + ".");
            }
        }

        return result;
    }

    @Override
    public Optional<String> getAuditInformationAsJson(String resourceIdentifier, Pageable pgbl) {
        LOGGER.trace("Performing getAuditInformation({}, {}).", resourceIdentifier, pgbl);
        return applicationProperties.getContentInformationAuditService().getAuditInformationAsJson(resourceIdentifier, pgbl.getPageNumber(), pgbl.getPageSize());
    }

    @Override
    public ContentInformation findById(String identifier) throws ResourceNotFoundException {
        LOGGER.trace("Performing findById({}).", identifier);
        Long id = Long.valueOf(identifier);
        Optional<ContentInformation> contentInformation = getDao().findById(id);
        if (!contentInformation.isPresent()) {
            //TODO: check later for collection download
            LOGGER.error("No content found for id {}. Throwing ResourceNotFoundException.", id);
            throw new ResourceNotFoundException("No content information for id " + id + " found.");
        }
        return contentInformation.get();
    }

    @Override
    public Page<ContentInformation> findByExample(ContentInformation example,
            List<String> callerIdentities,
            boolean callerIsAdmin,
            Pageable pgbl) {
        LOGGER.trace("Performing findByExample({}, {}).", example, pgbl);
        Page<ContentInformation> page;

        if (example == null) {
            //obtain all accessible content elements
            LOGGER.trace("No example provided. Returning all accessible content elements.");
            Specification<ContentInformation> spec = Specification.where(ContentInformationPermissionSpecification.toSpecification(null, callerIdentities, PERMISSION.READ));
            page = dao.findAll(spec, pgbl);
        } else {
            Specification<ContentInformation> spec;

            if (example.getParentResource() != null && example.getParentResource().getId() != null) {
                LOGGER.trace("Parent resource with id {} provided in example. Searching for content in single resource.", example.getParentResource().getId());
                spec = Specification.where(ContentInformationPermissionSpecification.toSpecification(example.getParentResource().getId(), callerIdentities, PERMISSION.READ));
            } else {
                LOGGER.trace("No parent resource provided in example. Searching for content in all resources.");
                spec = Specification.where(ContentInformationPermissionSpecification.toSpecification(null, callerIdentities, PERMISSION.READ));
            }

            LOGGER.trace("Adding additional query specifications based on example {}.", example);

            if (example.getRelativePath() != null) {
                LOGGER.trace("Adding relateive path query specification for relative path {}.", example.getRelativePath());
                spec = spec.and(ContentInformationRelativePathSpecification.toSpecification(example.getRelativePath(), false));
            }

            if (example.getContentUri() != null) {
                LOGGER.trace("Adding content Uri query specification for metadata {}.", example.getContentUri());
                spec = spec.and(ContentInformationContentUriSpecification.toSpecification(example.getContentUri(), false));
            }

            if (example.getMediaType() != null) {
                LOGGER.trace("Adding mediatype query specification for media type {}.", example.getMediaType());
                spec = spec.and(ContentInformationMediaTypeSpecification.toSpecification(example.getMediaType(), false));
            }

            if (example.getMetadata() != null && !example.getMetadata().isEmpty()) {
                LOGGER.trace("Adding metadata query specification for metadata {}.", example.getMetadata());
                spec = spec.and(ContentInformationMetadataSpecification.toSpecification(example.getMetadata()));
            }

            if (example.getTags() != null && !example.getTags().isEmpty()) {
                LOGGER.debug("Adding tag query specification for tags {}.", example.getTags());
                spec = spec.and(ContentInformationTagSpecification.toSpecification(example.getTags().toArray(new String[]{})));
            }
            LOGGER.trace("Calling findAll for collected specs and page information {}.", pgbl);
            page = dao.findAll(spec, pgbl);
        }

        LOGGER.trace("Returning page content.");
        return page;
    }

    @Override
    public Page<ContentInformation> findAll(ContentInformation c, Instant lastUpdateFrom, Instant lastUpdateUntil, Pageable pgbl) {
        LOGGER.trace("Performing findAll({}, {}, {}, {}).", c, lastUpdateFrom, lastUpdateUntil, pgbl);
        LOGGER.info("Obtaining content information from an lastUpdate range is not supported. Ignoring lastUpdate arguments.");
        return findAll(c, pgbl);
    }

    @Override
    public Page<ContentInformation> findAll(ContentInformation c, Pageable pgbl) {
        LOGGER.trace("Performing findAll({}, {}).", c, pgbl);

        if (c.getParentResource() == null) {
            LOGGER.error("Parent resource in template must not be null. Throwing CustomInternalServerError.");
            throw new CustomInternalServerError("Parent resource is missing from template.");
        }
        String parentId = c.getParentResource().getId();
        String relativePath = c.getRelativePath();
        Set<String> tags = c.getTags();
        //wrong header added!
        // eventPublisher.publishEvent(new PaginatedResultsRetrievedEvent<>(ContentInformation.class, uriBuilder, response, page.getNumber(), page.getTotalPages(), pageSize));
        Specification<ContentInformation> spec = Specification.where(ContentInformationMatchSpecification.toSpecification(parentId, relativePath, false));
        if (tags != null && !tags.isEmpty()) {
            LOGGER.debug("Content information tags {} provided. Using TagSpecification.", tags);
            spec = spec.and(ContentInformationTagSpecification.toSpecification(tags.toArray(new String[]{})));
        }
        return dao.findAll(spec, pgbl);
    }

    @Override
    @Transactional
    public void patch(ContentInformation resource, JsonPatch patch, Collection<? extends GrantedAuthority> userGrants) {
        LOGGER.trace("Performing patch({}, {}, {}).", "ContentInformation#" + resource.getId(), patch, userGrants);
        ContentInformation updated = PatchUtil.applyPatch(resource, patch, ContentInformation.class, userGrants);
        LOGGER.trace("Patch successfully applied.");

        long newVersion = applicationProperties.getContentInformationAuditService().getCurrentVersion(Long.toString(updated.getId())) + 1;
        LOGGER.trace("Setting new version number of content information to {}.", newVersion);
        updated.setVersion((int) newVersion);

        ContentInformation result = getDao().save(updated);
        LOGGER.trace("Resource successfully persisted.");

        LOGGER.trace("Capturing audit information.");
        applicationProperties.getContentInformationAuditService().captureAuditInformation(result, AuthenticationHelper.getPrincipal());

        LOGGER.trace("Sending UPDATE event.");
        messagingService.orElse(new LogfileMessagingService()).send(DataResourceMessage.factoryUpdateDataMessage(resource.getParentResource().getId(), updated.getRelativePath(), updated.getContentUri(), updated.getMediaType(), AuthenticationHelper.getPrincipal(), ControllerUtils.getLocalHostname()));
    }

    @Override
    @Transactional
    public void delete(ContentInformation resource) {
        LOGGER.trace("Performing delete({}).", "ContentInformation#" + resource.getId());
        getDao().delete(resource);

        LOGGER.trace("Deleting audit information.");
        applicationProperties.getContentInformationAuditService().deleteAuditInformation(Long.toString(resource.getId()), resource);

        LOGGER.trace("Sending DELETE event.");
        messagingService.orElse(new LogfileMessagingService()).send(DataResourceMessage.factoryDeleteDataMessage(resource.getParentResource().getId(), resource.getRelativePath(), resource.getContentUri(), resource.getMediaType(), AuthenticationHelper.getPrincipal(), ControllerUtils.getLocalHostname()));
    }

    protected IContentInformationDao getDao() {
        return dao;
    }

    @Override
    public Health health() {
        LOGGER.trace("Obtaining health information.");
        boolean repositoryPathAvailable = true;
        URL basePath = applicationProperties.getBasepath();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        Future<Boolean> future = executor.submit(() -> {
            File mount = new File(basePath.toURI());
            return mount.exists() && mount.isDirectory() && mount.canRead() && mount.list() != null;
        });

        try {
            LOGGER.trace("Checking repository path at {}.", basePath);
            Boolean accessible = future.get(5, TimeUnit.SECONDS);
            LOGGER.trace("Repository path is " + (accessible ? "accessible" : "not accessible"));
            repositoryPathAvailable = accessible;
        } catch (TimeoutException e) {
            LOGGER.error("Timeout while checking repository path.");
            repositoryPathAvailable = false;
        } catch (Exception e) {
            LOGGER.error("Error while checking repository path.", e);
            repositoryPathAvailable = false;
        } finally {
            executor.shutdownNow();
        }

        /*try {
            Path basePathAsPath = Paths.get(basePath.toURI());
            Path probe = Paths.get(basePathAsPath.toString(), "probe.txt");
            try {
                probe = Files.createFile(probe);
                Files.write(probe, "Success".getBytes());
            } catch (Throwable t) {
                LOGGER.error("Failed to check repository folder at " + basePath + ". Returning negative health status.", t);
                repositoryPathAvailable = false;
            } finally {
                try {
                    Files.deleteIfExists(probe);
                } catch (Throwable ignored) {
                }
            }
        } catch (URISyntaxException ex) {
            LOGGER.error("Invalid base path uri of " + basePath + ".", ex);
            repositoryPathAvailable = false;
        }*/
        if (repositoryPathAvailable) {
            return Health.up().withDetail("ContentInformation", dao.count()).build();
        } else {
            return Health.down().withDetail("ContentInformation", 0).build();
        }
    }

    @Override
    public ContentInformation put(ContentInformation c, ContentInformation c1, Collection<? extends GrantedAuthority> clctn) throws UpdateForbiddenException {
        String message = "PUT requests are not supported for this resource.";
        LOGGER.warn(message);
        throw new FeatureNotImplementedException(message);
    }
}
