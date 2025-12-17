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

import edu.kit.datamanager.entities.PERMISSION;
import edu.kit.datamanager.exceptions.BadArgumentException;
import edu.kit.datamanager.exceptions.CustomInternalServerError;
import edu.kit.datamanager.exceptions.ServiceUnavailableException;
import edu.kit.datamanager.repo.configuration.RepoBaseConfiguration;
import edu.kit.datamanager.repo.domain.ContentInformation;
import edu.kit.datamanager.repo.domain.DataResource;
import edu.kit.datamanager.util.ControllerUtils;
import edu.kit.datamanager.util.json.JsonPatch;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.HandlerMapping;

/**
 *
 * @author jejkal
 */
public class ContentDataUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContentDataUtils.class);

    private ContentDataUtils() {
    }

    /**
     * Create a new data resource.
     *
     * @param applicationProperties
     * @param resource
     * @param file
     * @param path
     * @param contentInformation
     * @param force
     * @param supplier
     * @return
     */
    public static ContentInformation addFile(RepoBaseConfiguration applicationProperties,
            DataResource resource,
            MultipartFile file,
            String path,
            ContentInformation contentInformation,
            boolean force,
            Function<String, String> supplier) {
        long nano1 = System.nanoTime() / 1000000;
        if (applicationProperties.isReadOnly()) {
            String message = "Repository is in read-only mode. Create content request denied.";
            LOGGER.info(message);
            throw new ServiceUnavailableException(message);
        }

        ControllerUtils.checkAnonymousAccess();
        long nano2 = System.nanoTime() / 1000000;
        //@TODO escape path properly
        if (path == null || path.length() == 0 || path.endsWith("/")) {
            String message = "Provided path is invalid. Path must not be empty and must not end with a slash.";
            LOGGER.info(message);
            throw new BadArgumentException(message);
        }
        //check data resource and permissions
        DataResourceUtils.performPermissionCheck(resource, PERMISSION.WRITE);
        long nano3 = System.nanoTime() / 1000000;

        try {
            ContentInformation result = applicationProperties.getContentInformationService().create(contentInformation, resource, path, (file != null) ? file.getInputStream() : null, force);
            long nano4 = System.nanoTime() / 1000000;
            LOGGER.info("Add file, {}, {}, {}, {}", nano1, nano2 - nano1, nano3 - nano1, nano4 - nano1);

            return result;
        } catch (IOException ex) {
            LOGGER.error("Failed to open file input stream.", ex);
            throw new CustomInternalServerError("Unable to read from stream. Upload canceled.");
        }
    }

    /**
     * Read an existing resource.
     *
     * @param applicationProperties
     * @param path
     * @param resource
     * @param version
     * @param supplier
     * @return
     */
    public static ContentInformation readFile(RepoBaseConfiguration applicationProperties,
            DataResource resource,
            String path,
            Long version,
            Function<String, String> supplier) {
        ContentInformation newDataResource = null;
        return newDataResource;
    }

    /**
     * Read all existing resources.
     *
     * @param applicationProperties
     * @param resource
     * @param path
     * @param tag
     * @param version
     * @param pgbl
     * @param supplier
     * @return
     */
    public static List<ContentInformation> readFiles(RepoBaseConfiguration applicationProperties,
            DataResource resource,
            String path,
            String tag,
            Long version,
            Pageable pgbl,
            Function<String, String> supplier) {
        DataResourceUtils.performPermissionCheck(resource, PERMISSION.READ);
        List<ContentInformation> contentInformationList = new ArrayList<>();
        LOGGER.trace("Checking provided path {}.", path);
        if (path.startsWith("/")) {
            LOGGER.debug("Removing leading slash from path {}.", path);
            //remove leading slash if present, e.g. if path was empty
            path = path.substring(1);
        }

        //switch between collection and element listing
        if (path.endsWith("/") || path.length() == 0) {
            LOGGER.trace("Path ends with slash or is empty. Performing collection access.");
            //collection listing
            path += "%";
            //sanitize page request

            PageRequest pageRequest = ControllerUtils.checkPaginationInformation(pgbl, pgbl.getSort().equals(Sort.unsorted()) ? Sort.by(Sort.Order.asc("depth"), Sort.Order.asc("relativePath")) : pgbl.getSort());

            LOGGER.trace("Obtaining content information page for parent resource {}, path {} and tag {}. Page information are: {}", resource.getId(), path, tag, pageRequest);
            Page<ContentInformation> resultList = applicationProperties.getContentInformationService().findAll(ContentInformation.createContentInformation(resource.getId(), path, tag), pageRequest);
            contentInformationList = resultList.getContent();
        } else {
            LOGGER.trace("Path does not end with slash and/or is not empty. Assuming single element access.");
            ContentInformation contentInformation = applicationProperties.getContentInformationService().getContentInformation(resource.getId(), path, version);
            contentInformationList.add(contentInformation);
        }
        return contentInformationList;
    }

    /**
     * Delete an existing resource.
     *
     * @param applicationProperties
     * @param identifier
     * @param path
     * @param eTag
     * @param deleteContent
     */
    public static void deleteFile(RepoBaseConfiguration applicationProperties,
            String identifier,
            String path,
            String eTag,
            Function<String, String> deleteContent) {
        if (applicationProperties.isReadOnly()) {
            String message = "Repository is in read-only mode. Delete content request denied.";
            LOGGER.info(message);
            throw new ServiceUnavailableException(message);
        }
        //check resource and permission
        ControllerUtils.checkAnonymousAccess();
        DataResource resource = DataResourceUtils.getResourceByIdentifierOrRedirect(applicationProperties, identifier, null, deleteContent);

        DataResourceUtils.performPermissionCheck(resource, PERMISSION.ADMINISTRATE);


        //try to obtain single content element matching path exactly
        Page<ContentInformation> contentInfoOptional = applicationProperties.getContentInformationService().findAll(ContentInformation.createContentInformation(resource.getId(), path), PageRequest.of(0, 1));
        if (contentInfoOptional.hasContent()) {
            LOGGER.debug("Content information entry found. Checking ETag.");

            ContentInformation contentInfo = contentInfoOptional.getContent().get(0);
            ControllerUtils.checkEtag(eTag, contentInfo);

            Path localContentToRemove = null;
            URI contentUri = URI.create(contentInfo.getContentUri());
            LOGGER.trace("Checking if content URI {} is pointing to a local file.", contentInfo);
            if ("file".equals(contentUri.getScheme())) {
                //mark file for removal
                localContentToRemove = Paths.get(URI.create(contentInfo.getContentUri()));
            } else {
                //content URI is not pointing to a file...just replace the entry
                LOGGER.trace("Content to delete is pointing to {}. Local content deletion will be skipped.", contentInfo.getContentUri());
            }
            applicationProperties.getContentInformationService().delete(contentInfo);

            if (localContentToRemove != null) {
                try {
                    LOGGER.trace("Removing content file {}.", localContentToRemove);
                    Files.deleteIfExists(localContentToRemove);
                } catch (IOException ex) {
                    LOGGER.warn("Failed to remove data at " + localContentToRemove + ". Manual removal required.", ex);
                }
            } else {
                LOGGER.trace("No local content file exists. Returning from DELETE.");
            }
        }
    }

    /**
     * Patch an existing data resource.
     *
     * @param applicationProperties
     * @param identifier
     * @param path
     * @param patch
     * @param eTag
     * @param patchContentInformation
     * @return
     */
    public static ContentInformation patchContentInformation(RepoBaseConfiguration applicationProperties,
            String identifier,
            String path,
            JsonPatch patch,
            String eTag,
            Function<String, String> patchContentInformation) {
        ContentInformation toUpdate;
        if (applicationProperties.isReadOnly()) {
            String message = "Repository is in read-only mode. Patch content metadata request denied.";
            LOGGER.info(message);
            throw new ServiceUnavailableException(message);
        }

        ControllerUtils.checkAnonymousAccess();

        DataResource resource = DataResourceUtils.getResourceByIdentifierOrRedirect(applicationProperties, identifier, null, patchContentInformation);

        DataResourceUtils.performPermissionCheck(resource, PERMISSION.WRITE);

        toUpdate = applicationProperties.getContentInformationService().getContentInformation(resource.getId(), path, null);
        ControllerUtils.checkEtag(eTag, toUpdate);

        applicationProperties.getContentInformationService().patch(toUpdate, patch, DataResourceUtils.getUserAuthorities(resource));
        return toUpdate;
    }

    public static String getContentPathFromRequest(WebRequest request) {
        String requestedUri = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, WebRequest.SCOPE_REQUEST);
        if (requestedUri == null) {
            String message = "Unable to obtain request URI.";
            LOGGER.info(message);
            throw new CustomInternalServerError(message);
        }
        return requestedUri.substring(requestedUri.indexOf("data/") + "data/".length());
    }

    public static ContentInformation filterContentInformation(ContentInformation resource) {
        //hide all attributes but the id from the parent data resource in the content information entity

        String id = resource.getParentResource().getId();
        DataResource dataResource = new DataResource();
        dataResource.setId(id);
        resource.setParentResource(dataResource);
        return resource;
    }

    public static List<ContentInformation> filterContentInformation(List<ContentInformation> resources) {
        //hide all attributes but the id from the parent data resource in all content information entities
        resources.forEach((resource) -> {
            filterContentInformation(resource);
        });
        return resources;
    }

}
