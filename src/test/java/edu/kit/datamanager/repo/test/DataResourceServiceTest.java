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
package edu.kit.datamanager.repo.test;

import com.fasterxml.jackson.core.JsonProcessingException;
import edu.kit.datamanager.entities.Identifier;
import edu.kit.datamanager.entities.PERMISSION;
import edu.kit.datamanager.entities.RepoUserRole;
import edu.kit.datamanager.exceptions.BadArgumentException;
import edu.kit.datamanager.exceptions.ResourceAlreadyExistException;
import edu.kit.datamanager.exceptions.ResourceNotFoundException;
import edu.kit.datamanager.repo.configuration.DateBasedStorageProperties;
import edu.kit.datamanager.repo.configuration.RepoBaseConfiguration;
import edu.kit.datamanager.repo.configuration.StorageServiceProperties;
import edu.kit.datamanager.repo.dao.IDataResourceDao;
import edu.kit.datamanager.repo.domain.Agent;
import edu.kit.datamanager.repo.domain.DataResource;
import edu.kit.datamanager.repo.domain.ResourceType;
import edu.kit.datamanager.repo.domain.Title;
import edu.kit.datamanager.repo.domain.UnknownInformationConstants;
import edu.kit.datamanager.repo.domain.acl.AclEntry;
import edu.kit.datamanager.repo.service.IDataResourceService;
import edu.kit.datamanager.repo.service.impl.DataResourceAuditService;
import edu.kit.datamanager.repo.service.impl.DateBasedStorageService;
import edu.kit.datamanager.repo.service.impl.NoneDataVersioningService;
import edu.kit.datamanager.repo.test.integration.DataResourceControllerTest;
import edu.kit.datamanager.security.filter.JwtAuthenticationToken;
import edu.kit.datamanager.util.AuthenticationHelper;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Calendar;
import org.javers.core.Javers;
import org.javers.core.JaversBuilder;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.internal.verification.VerificationModeFactory;
import org.mockito.verification.VerificationMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.transaction.TransactionalTestExecutionListener;

/**
 *
 * @author jejkal
 */
@RunWith(SpringRunner.class)
/*@PowerMockRunnerDelegate(SpringJUnit4ClassRunner.class)
@PowerMockIgnore({"jakarta.crypto.*", "jakarta.management.*"})
@PrepareForTest(AuthenticationHelper.class)*/
@SpringBootTest
@AutoConfigureMockMvc
@TestExecutionListeners(listeners = {
    DependencyInjectionTestExecutionListener.class,
    TransactionalTestExecutionListener.class
})
@ActiveProfiles("test")
public class DataResourceServiceTest {

    @Autowired
    private IDataResourceService service;
    @Autowired
    private IDataResourceDao dao;

    private RepoBaseConfiguration rbc;
    @Autowired
    private StorageServiceProperties storageServiceProperties;

    @Before
    public void cleanDbBefore() throws MalformedURLException {
        //configure service
        rbc = new RepoBaseConfiguration();
        rbc.setBasepath(new URL("file:///tmp/repo-base"));
        DateBasedStorageService s = new DateBasedStorageService();
        s.configure(storageServiceProperties);
        rbc.setStorageService(s);
        rbc.setReadOnly(false);
        rbc.setVersioningService(new NoneDataVersioningService());
        Javers javers = JaversBuilder.javers().build();
        rbc.setAuditService(new DataResourceAuditService(javers, rbc));
        service.configure(rbc);
        try {
            dao.deleteAll();
            dao.flush();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @After
    public void cleanDbAfter() {
        try {
            dao.deleteAll();
            dao.flush();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @Test
    public void testCreateWithTitleAndType() {
        DataResource resource = createResourceWithDoi("testDoi1", "MyResource", "SimpleResource");
        resource.setState(DataResource.State.VOLATILE);
        resource = service.create(resource, AuthenticationHelper.ANONYMOUS_USER_PRINCIPAL);
        //check if id was assigned
        Assert.assertNotNull(resource.getId());
        //check if publisher was assigned and is caller
        Assert.assertNotNull(resource.getPublisher());
        Assert.assertEquals(AuthenticationHelper.ANONYMOUS_USER_PRINCIPAL, resource.getPublisher());
        //check if publication year is assigned and current year
        Assert.assertNotNull(resource.getPublicationYear());
        Assert.assertEquals(Integer.toString(Calendar.getInstance().get(Calendar.YEAR)), resource.getPublicationYear());

        //check if creator is added and equals caller
        Assert.assertEquals(1, resource.getCreators().size());
        Assert.assertEquals(AuthenticationHelper.ANONYMOUS_USER_PRINCIPAL, resource.getCreators().toArray(new Agent[]{})[0].getGivenName());

        //check if acl is set and includes ADMINISTRATE permissions for caller
        Assert.assertEquals(1, resource.getAcls().size());
        Assert.assertEquals(AuthenticationHelper.ANONYMOUS_USER_PRINCIPAL, resource.getAcls().toArray(new AclEntry[]{})[0].getSid());
        Assert.assertEquals(PERMISSION.ADMINISTRATE, resource.getAcls().toArray(new AclEntry[]{})[0].getPermission());

        //check manually assigned values
        Assert.assertEquals("testDoi1", resource.getIdentifier().getValue());

        Assert.assertEquals(1, resource.getAlternateIdentifiers().size());
        Assert.assertEquals("testDoi1", resource.getAlternateIdentifiers().toArray(new Identifier[]{})[0].getValue());
        Assert.assertEquals(Identifier.IDENTIFIER_TYPE.INTERNAL, resource.getAlternateIdentifiers().toArray(new Identifier[]{})[0].getIdentifierType());

        Assert.assertEquals(1, resource.getTitles().size());
        Assert.assertEquals("MyResource", resource.getTitles().toArray(new Title[]{})[0].getValue());
        Assert.assertEquals(Title.TYPE.TRANSLATED_TITLE, resource.getTitles().toArray(new Title[]{})[0].getTitleType());

        Assert.assertEquals("SimpleResource", resource.getResourceType().getValue());
        Assert.assertEquals(ResourceType.TYPE_GENERAL.DATASET, resource.getResourceType().getTypeGeneral());
    }

    @Test
    public void testIdAssignment() {
        //test with doi
        DataResource resource = createResourceWithDoi("testDoi2", "MyResource", "SimpleResource");
        resource = service.create(resource, AuthenticationHelper.ANONYMOUS_USER_PRINCIPAL);
        //check manually assigned values
        Assert.assertEquals("testDoi2", resource.getIdentifier().getValue());

        Assert.assertEquals(1, resource.getAlternateIdentifiers().size());
        Assert.assertEquals("testDoi2", resource.getAlternateIdentifiers().toArray(new Identifier[]{})[0].getValue());
        Assert.assertEquals(Identifier.IDENTIFIER_TYPE.INTERNAL, resource.getAlternateIdentifiers().toArray(new Identifier[]{})[0].getIdentifierType());

        Assert.assertEquals("testDoi2", resource.getId());

        //test without doi
        resource = createResourceWithoutDoi("internalId", "MyResource", "SimpleResource");
        resource = service.create(resource, AuthenticationHelper.ANONYMOUS_USER_PRINCIPAL);

        Assert.assertEquals(UnknownInformationConstants.TO_BE_ASSIGNED_OR_ANNOUNCED_LATER.getValue(), resource.getIdentifier().getValue());

        Assert.assertEquals(1, resource.getAlternateIdentifiers().size());
        Assert.assertEquals("internalId", resource.getAlternateIdentifiers().toArray(new Identifier[]{})[0].getValue());
        Assert.assertEquals(Identifier.IDENTIFIER_TYPE.INTERNAL, resource.getAlternateIdentifiers().toArray(new Identifier[]{})[0].getIdentifierType());
        Assert.assertEquals("internalId", resource.getId());

        //test unknown information constant as doi
        resource = createResourceWithDoi(UnknownInformationConstants.EXPLICITLY_AND_MEANINGFUL_EMPTY.getValue(), "MyResource", "SimpleResource");
        resource = service.create(resource, AuthenticationHelper.ANONYMOUS_USER_PRINCIPAL);
        Assert.assertEquals(UnknownInformationConstants.EXPLICITLY_AND_MEANINGFUL_EMPTY.getValue(), resource.getIdentifier().getValue());

        //test with null internal id
        resource = createResourceWithoutDoi("internalId", "MyResource", "SimpleResource");
        resource.getAlternateIdentifiers().clear();
        resource.getAlternateIdentifiers().add(Identifier.factoryInternalIdentifier(null));
        try {
            resource = service.create(resource, AuthenticationHelper.ANONYMOUS_USER_PRINCIPAL);
            Assert.fail("Test should have failed already, but resource " + resource + " has been created.");
        } catch (BadArgumentException ex) {
        }

        //test without any identifier
        resource = createResourceWithoutDoi("internalId", "MyResource", "SimpleResource");
        resource.getAlternateIdentifiers().clear();
        resource = service.create(resource, AuthenticationHelper.ANONYMOUS_USER_PRINCIPAL);

        Assert.assertEquals(UnknownInformationConstants.TO_BE_ASSIGNED_OR_ANNOUNCED_LATER.getValue(), resource.getIdentifier().getValue());

        Assert.assertEquals(1, resource.getAlternateIdentifiers().size());
        String internalIdentifier = resource.getAlternateIdentifiers().toArray(new Identifier[]{})[0].getValue();
        Assert.assertEquals(Identifier.IDENTIFIER_TYPE.INTERNAL, resource.getAlternateIdentifiers().toArray(new Identifier[]{})[0].getIdentifierType());

        Assert.assertEquals(internalIdentifier, resource.getId());
    }

    @Test
    public void testFindById() {
        DataResource resource = createResourceWithDoi("simpleDoi", "MyResource", "SimpleResource");
        resource = service.create(resource, AuthenticationHelper.ANONYMOUS_USER_PRINCIPAL);
        DataResource found = service.findById("simpleDoi");
        Assert.assertNotNull(found);
        Assert.assertEquals("simpleDoi", found.getId());
    }

    @Test(expected = ResourceNotFoundException.class)
    public void testFindByUnknownId() {
        DataResource found = service.findById("NotExist");
        Assert.fail("Test should have failed already.");
    }

    @Test(expected = ResourceAlreadyExistException.class)
    public void testDoubleResourceRegistration() {
        DataResource resource = createResourceWithDoi("testDoi3", "MyResource", "SimpleResource");
        resource = service.create(resource, AuthenticationHelper.ANONYMOUS_USER_PRINCIPAL);
        resource = service.create(resource, AuthenticationHelper.ANONYMOUS_USER_PRINCIPAL);
        Assert.fail("Test should have failed already, but resource " + resource + " has been created twice.");
    }

    @Test(expected = BadArgumentException.class)
    public void testCreateWithoutTitle() {
        DataResource resource = createResourceWithDoi("testDoi4", null, null);
        resource = service.create(resource, AuthenticationHelper.ANONYMOUS_USER_PRINCIPAL);
        Assert.fail("Test should have failed already, but resource " + resource + " has been created.");
    }

    @Test
    public void testCreateResourceWithCreators() {
        DataResource resource = createResourceWithDoi("testDoi5", "My Resource", "SimpleResource");
        resource.getCreators().add(Agent.factoryAgent("test", "user"));
        resource = service.create(resource, AuthenticationHelper.ANONYMOUS_USER_PRINCIPAL);

        //check if creator was used and no other creator has been added
        Assert.assertEquals(1, resource.getCreators().size());
        Assert.assertEquals("test", resource.getCreators().toArray(new Agent[]{})[0].getGivenName());
        Assert.assertEquals("user", resource.getCreators().toArray(new Agent[]{})[0].getFamilyName());

        //check if acl is set and includes ADMINISTRATE permissions for caller
        Assert.assertEquals(1, resource.getAcls().size());
        Assert.assertEquals(AuthenticationHelper.ANONYMOUS_USER_PRINCIPAL, resource.getAcls().toArray(new AclEntry[]{})[0].getSid());
        Assert.assertEquals(PERMISSION.ADMINISTRATE, resource.getAcls().toArray(new AclEntry[]{})[0].getPermission());
    }

    @Test
    @Ignore
    public void testCreateResourceWithWithFullAuthentication() throws JsonProcessingException {
        mockJwtUserAuthentication();
        DataResource resource = createResourceWithDoi("testDoi6", "My Resource", "SimpleResource");
        resource = service.create(resource, "tester", "test", "user");

        //check if creator was used and no other creator has been added
        Assert.assertEquals(1, resource.getCreators().size());
        Assert.assertEquals("test", resource.getCreators().toArray(new Agent[]{})[0].getGivenName());
        Assert.assertEquals("user", resource.getCreators().toArray(new Agent[]{})[0].getFamilyName());

        //check if acl is set and includes ADMINISTRATE permissions for caller
        Assert.assertEquals(1, resource.getAcls().size());
        Assert.assertEquals("tester", resource.getAcls().toArray(new AclEntry[]{})[0].getSid());
        Assert.assertEquals(PERMISSION.ADMINISTRATE, resource.getAcls().toArray(new AclEntry[]{})[0].getPermission());
        Mockito.verify(AuthenticationHelper.class);
       // PowerMockito.verifyStatic(AuthenticationHelper.class);
    }

    @Test(expected = BadArgumentException.class)
    public void testCreateWithTitleAndWithoutType() {
        DataResource resource = createResourceWithDoi("testDoi7", "MyResource", null);
        resource = service.create(resource, AuthenticationHelper.ANONYMOUS_USER_PRINCIPAL);
        Assert.fail("Test should have failed already, but resource " + resource + " has been created.");
    }

    private DataResource createResourceWithDoi(String pid, String title, String type) {
        DataResource resource;

        resource = DataResource.factoryDataResourceWithDoi(pid);

        if (title != null) {
            resource.getTitles().add(Title.factoryTitle(title, Title.TYPE.TRANSLATED_TITLE));
        }
        if (type != null) {
            resource.setResourceType(ResourceType.createResourceType(type));
        }
        return resource;
    }

    private DataResource createResourceWithoutDoi(String iid, String title, String type) {
        DataResource resource;

        resource = DataResource.factoryNewDataResource(iid);

        if (title != null) {
            resource.getTitles().add(Title.factoryTitle(title, Title.TYPE.TRANSLATED_TITLE));
        }
        if (type != null) {
            resource.setResourceType(ResourceType.createResourceType(type));
        }
        return resource;
    }

    private void mockJwtUserAuthentication() throws JsonProcessingException {
        
        JwtAuthenticationToken userToken = edu.kit.datamanager.util.JwtBuilder.
                createUserToken("tester", RepoUserRole.ADMINISTRATOR).
                addSimpleClaim("firstname", "test").
                addSimpleClaim("lastname", "user").
                addSimpleClaim("email", "test@mail.org").
                addSimpleClaim("groupid", "USERS").
                getJwtAuthenticationToken(DataResourceControllerTest.KEYCLOAK_SECRET);
          try (MockedStatic<AuthenticationHelper> utilities = Mockito.mockStatic(AuthenticationHelper.class)) {
        utilities.when(AuthenticationHelper::getAuthentication).thenReturn(userToken);
         /*utilities.when(AuthenticationHelper::hasAuthority).thenCallRealMethod;
        when(AuthenticationHelper.getAuthentication()).thenReturn(userToken);
        when(AuthenticationHelper.hasAuthority(any(String.class))).thenCallRealMethod();
        when(AuthenticationHelper.hasIdentity(any(String.class))).thenCallRealMethod();
        when(AuthenticationHelper.getPrincipal()).thenCallRealMethod();
        when(AuthenticationHelper.getAuthorizationIdentities()).thenCallRealMethod();
        when(AuthenticationHelper.getScopedPermission(any(String.class), any(String.class))).thenCallRealMethod();*/
        

        }
          
        /*PowerMockito.mockStatic(AuthenticationHelper.class);
        when(AuthenticationHelper.getAuthentication()).thenReturn(userToken);
        when(AuthenticationHelper.hasAuthority(RepoUserRole.ADMINISTRATOR.getValue())).thenCallRealMethod();
        when(AuthenticationHelper.getFirstname()).thenCallRealMethod();
        when(AuthenticationHelper.getLastname()).thenCallRealMethod();
        when(AuthenticationHelper.getPrincipal()).thenCallRealMethod();
        when(AuthenticationHelper.getAuthorizationIdentities()).thenCallRealMethod();
        when(AuthenticationHelper.getScopedPermission(any(String.class), any(String.class))).thenCallRealMethod();*/
    }
}
