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
import edu.kit.datamanager.entities.RepoServiceRole;
import edu.kit.datamanager.entities.RepoUserRole;
import edu.kit.datamanager.exceptions.AccessForbiddenException;
import edu.kit.datamanager.exceptions.ResourceNotFoundException;
import edu.kit.datamanager.repo.domain.DataResource;
import edu.kit.datamanager.repo.domain.acl.AclEntry;
import edu.kit.datamanager.repo.test.integration.DataResourceControllerTest;
import edu.kit.datamanager.repo.util.DataResourceUtils;
import edu.kit.datamanager.security.filter.JwtAuthenticationToken;
import edu.kit.datamanager.security.filter.ScopedPermission;
import edu.kit.datamanager.util.AuthenticationHelper;
import edu.kit.datamanager.util.JwtBuilder;
import org.junit.Assert;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 *
 * @author jejkal
 */
public class DataResourceUtilsTest {

    @Test
    public void testGetInternalIdentifier() {
        DataResource res = DataResource.factoryNewDataResource();
        res.getAlternateIdentifiers().add(Identifier.factoryIdentifier("other", Identifier.IDENTIFIER_TYPE.OTHER));
        Assert.assertNotNull(DataResourceUtils.getInternalIdentifier(res));
        res = DataResource.factoryNewDataResource("internal_identifier");
        Assert.assertNotNull(DataResourceUtils.getInternalIdentifier(res));
        Assert.assertEquals("internal_identifier", DataResourceUtils.getInternalIdentifier(res));
        res = DataResource.factoryNewDataResource("internal_identifier");
        //clear alternate identifiers (should never happen)
        res.getAlternateIdentifiers().clear();
        //use only 'OTHER' alternate identifier (should also never happen)
        Assert.assertNull(DataResourceUtils.getInternalIdentifier(res));
        res.getAlternateIdentifiers().add(Identifier.factoryIdentifier("other", Identifier.IDENTIFIER_TYPE.OTHER));
        Assert.assertNull(DataResourceUtils.getInternalIdentifier(res));
    }

    @Test
    public void testGetAccessPermission() {
        DataResource res = DataResource.factoryNewDataResource();
        res.getAcls().add(new AclEntry("tester", PERMISSION.WRITE));
        //check normal permission from ACL entry

        try (MockedStatic<AuthenticationHelper> utilities = Mockito.mockStatic(AuthenticationHelper.class)) {
            setupUtilities(utilities, mockJwtUserAuthentication(RepoUserRole.USER));
            Assert.assertEquals(PERMISSION.WRITE, DataResourceUtils.getAccessPermission(res));
            res.getAcls().clear();
           
            //check NONE permission if no ACL entry exists
            Assert.assertEquals(PERMISSION.NONE, DataResourceUtils.getAccessPermission(res));

            //add NONE permission for user and test for NONE access via ACL
            res.getAcls().add(new AclEntry("tester", PERMISSION.NONE));
            Assert.assertEquals(PERMISSION.NONE, DataResourceUtils.getAccessPermission(res));
            res.getAcls().clear();
        }

        //check as ADMIN without ACL entry
        //first, assign ADMINISTRATOR role by authorization
        //permission are now ADMINISTRATE, ACL entries are ignored
        try (MockedStatic<AuthenticationHelper> utilities = Mockito.mockStatic(AuthenticationHelper.class)) {
            setupUtilities(utilities, mockJwtUserAuthentication(RepoUserRole.ADMINISTRATOR));
            Assert.assertEquals(PERMISSION.ADMINISTRATE, DataResourceUtils.getAccessPermission(res));
        }
    }

    @Test
    public void testHasPermission() {
        //build rtesource with acls
        //mock authorization helper
        DataResource res = DataResource.factoryNewDataResource();
        res.getAcls().add(new AclEntry("tester", PERMISSION.WRITE));

        try (MockedStatic<AuthenticationHelper> utilities = Mockito.mockStatic(AuthenticationHelper.class)) {
            setupUtilities(utilities, mockJwtUserAuthentication(RepoUserRole.USER));
            //check normal permission from ACL entry
            Assert.assertFalse(DataResourceUtils.hasPermission(res, PERMISSION.ADMINISTRATE));
            Assert.assertTrue(DataResourceUtils.hasPermission(res, PERMISSION.WRITE));
            Assert.assertTrue(DataResourceUtils.hasPermission(res, PERMISSION.READ));
            Assert.assertTrue(DataResourceUtils.hasPermission(res, PERMISSION.NONE));

            res.getAcls().clear();
            //check NONE permission if no ACL entry exists
            Assert.assertFalse(DataResourceUtils.hasPermission(res, PERMISSION.ADMINISTRATE));
            Assert.assertFalse(DataResourceUtils.hasPermission(res, PERMISSION.WRITE));
            Assert.assertFalse(DataResourceUtils.hasPermission(res, PERMISSION.READ));
            Assert.assertTrue(DataResourceUtils.hasPermission(res, PERMISSION.NONE));
        }
        //check as ADMIN without ACL entry
        //first, assign ADMINISTRATOR role by authorization

        try (MockedStatic<AuthenticationHelper> utilities = Mockito.mockStatic(AuthenticationHelper.class)) {
            setupUtilities(utilities, mockJwtUserAuthentication(RepoUserRole.ADMINISTRATOR));
            //permission are now ADMINISTRATE, ACL entries are ignored
            Assert.assertTrue(DataResourceUtils.hasPermission(res, PERMISSION.ADMINISTRATE));
            Assert.assertTrue(DataResourceUtils.hasPermission(res, PERMISSION.WRITE));
            Assert.assertTrue(DataResourceUtils.hasPermission(res, PERMISSION.READ));
            Assert.assertTrue(DataResourceUtils.hasPermission(res, PERMISSION.NONE));
        }
    }

    @Test
    public void testPermissionCheckSuccessful() {
        DataResource res = DataResource.factoryNewDataResource();
        res.setState(DataResource.State.VOLATILE);
        res.getAcls().add(new AclEntry("tester", PERMISSION.WRITE));

        try (MockedStatic<AuthenticationHelper> utilities = Mockito.mockStatic(AuthenticationHelper.class)) {
            setupUtilities(utilities, mockJwtUserAuthentication(RepoUserRole.USER));
            DataResourceUtils.performPermissionCheck(res, PERMISSION.WRITE);
            DataResourceUtils.performPermissionCheck(res, PERMISSION.READ);
            DataResourceUtils.performPermissionCheck(res, PERMISSION.NONE);
        }
    }

    @Test
    public void testPermissionCheckWithoutState() {
        DataResource res = DataResource.factoryNewDataResource();
        res.getAcls().add(new AclEntry("tester", PERMISSION.WRITE));

        try (MockedStatic<AuthenticationHelper> utilities = Mockito.mockStatic(AuthenticationHelper.class)) {
            setupUtilities(utilities, mockJwtUserAuthentication(RepoUserRole.USER));
            DataResourceUtils.performPermissionCheck(res, PERMISSION.WRITE);
            DataResourceUtils.performPermissionCheck(res, PERMISSION.READ);
            DataResourceUtils.performPermissionCheck(res, PERMISSION.NONE);
        }
    }

    @Test(expected = AccessForbiddenException.class)
    public void testPermissionCheckFail() {
        DataResource res = DataResource.factoryNewDataResource();
        res.setState(DataResource.State.VOLATILE);
        res.getAcls().add(new AclEntry("tester", PERMISSION.WRITE));
       
        try (MockedStatic<AuthenticationHelper> utilities = Mockito.mockStatic(AuthenticationHelper.class)) {
            setupUtilities(utilities, mockJwtUserAuthentication(RepoUserRole.USER));
            DataResourceUtils.performPermissionCheck(res, PERMISSION.ADMINISTRATE);
        }
    }

    @Test(expected = AccessForbiddenException.class)
    public void testPermissionCheckOnFixedResourceWithoutAdministratePermissions() {
        DataResource res = DataResource.factoryNewDataResource();
        res.setState(DataResource.State.FIXED);
        //user has WRITE permissions, resource is fixed, requesting WRITE permissions should fail
        res.getAcls().add(new AclEntry("tester", PERMISSION.WRITE));
       
        try (MockedStatic<AuthenticationHelper> utilities = Mockito.mockStatic(AuthenticationHelper.class)) {
            setupUtilities(utilities, mockJwtUserAuthentication(RepoUserRole.USER));

            DataResourceUtils.performPermissionCheck(res, PERMISSION.WRITE);
        }
    }

    @Test
    public void testPermissionCheckReadOnFixedResourceWithoutAdministratePermissions() {
        DataResource res = DataResource.factoryNewDataResource();
        res.setState(DataResource.State.FIXED);
        //user has READ permissions, resource is fixed, requesting READ permissions should succeed
        res.getAcls().add(new AclEntry("tester", PERMISSION.READ));

        try (MockedStatic<AuthenticationHelper> utilities = Mockito.mockStatic(AuthenticationHelper.class)) {
            setupUtilities(utilities, mockJwtUserAuthentication(RepoUserRole.USER));
            DataResourceUtils.performPermissionCheck(res, PERMISSION.READ);
        }
    }

    @Test
    public void testPermissionCheckOnFixedResourceWithAdministratePermissions() {
        DataResource res = DataResource.factoryNewDataResource();
        res.setState(DataResource.State.FIXED);
        //user has ADMINISTRATE permissions, resource is fixed, requesting WRITE permissions should succeed
        res.getAcls().add(new AclEntry("tester", PERMISSION.ADMINISTRATE));
       
        try (MockedStatic<AuthenticationHelper> utilities = Mockito.mockStatic(AuthenticationHelper.class)) {
            setupUtilities(utilities, mockJwtUserAuthentication(RepoUserRole.USER));
            DataResourceUtils.performPermissionCheck(res, PERMISSION.WRITE);
        }
    }

    @Test
    public void testPermissionCheckOnFixedResourceAsAdministrator() {
        DataResource res = DataResource.factoryNewDataResource();
        res.setState(DataResource.State.FIXED);
        //user has READ permissions but has also role ADMINISTRATOR, resource is fixed, requesting WRITE permissions should succeed
        res.getAcls().add(new AclEntry("tester", PERMISSION.READ));

        try (MockedStatic<AuthenticationHelper> utilities = Mockito.mockStatic(AuthenticationHelper.class)) {
            setupUtilities(utilities, mockJwtUserAuthentication(RepoUserRole.ADMINISTRATOR));
            DataResourceUtils.performPermissionCheck(res, PERMISSION.WRITE);
        }
    }

    @Test(expected = ResourceNotFoundException.class)
    public void testPermissionCheckOnRevokedResourceWithoutAdministratePermissions() {
        DataResource res = DataResource.factoryNewDataResource();
        res.setState(DataResource.State.REVOKED);
        //user has WRITE permissions, resource is revoked, requesting READ permissions should fail, resource should not be found
        res.getAcls().add(new AclEntry("tester", PERMISSION.WRITE));

        try (MockedStatic<AuthenticationHelper> utilities = Mockito.mockStatic(AuthenticationHelper.class)) {
            setupUtilities(utilities, mockJwtUserAuthentication(RepoUserRole.USER));
            DataResourceUtils.performPermissionCheck(res, PERMISSION.READ);
        }
    }

    @Test
    public void testPermissionCheckOnRevokedResourceWithAdministratePermissions() {
        DataResource res = DataResource.factoryNewDataResource();
        res.setState(DataResource.State.REVOKED);
        //user has ADMINISTRATE permissions, resource is revoked, requesting READ permissions should succeed
        res.getAcls().add(new AclEntry("tester", PERMISSION.ADMINISTRATE));
        try (MockedStatic<AuthenticationHelper> utilities = Mockito.mockStatic(AuthenticationHelper.class)) {
            setupUtilities(utilities, mockJwtUserAuthentication(RepoUserRole.USER));
            DataResourceUtils.performPermissionCheck(res, PERMISSION.READ);
        }
    }

    @Test
    public void testPermissionCheckOnRevokedResourceAsAdministrator() {
        DataResource res = DataResource.factoryNewDataResource();
        res.setState(DataResource.State.REVOKED);
        //user has READ permissions but has also role ADMINISTRATOR, resource is revoked, requesting READ permissions should succeed
        res.getAcls().add(new AclEntry("tester", PERMISSION.READ));

        try (MockedStatic<AuthenticationHelper> utilities = Mockito.mockStatic(AuthenticationHelper.class)) {
            setupUtilities(utilities, mockJwtUserAuthentication(RepoUserRole.ADMINISTRATOR));
            DataResourceUtils.performPermissionCheck(res, PERMISSION.READ);
        }
    }

    @Test
    public void testPermissionCheckAsServiceUser() throws JsonProcessingException {
        DataResource res = DataResource.factoryNewDataResource();
        res.getAcls().add(new AclEntry("tester", PERMISSION.WRITE));

        JwtAuthenticationToken serviceToken = mockJwtServiceAuthentication(RepoServiceRole.SERVICE_READ);
        try (MockedStatic<AuthenticationHelper> utilities = Mockito.mockStatic(AuthenticationHelper.class)) {
            setupUtilities(utilities, serviceToken);
            Assert.assertEquals(PERMISSION.READ, DataResourceUtils.getAccessPermission(res));
            Assert.assertTrue(DataResourceUtils.hasPermission(res, PERMISSION.READ));
            Assert.assertFalse(DataResourceUtils.hasPermission(res, PERMISSION.WRITE));
            Assert.assertFalse(DataResourceUtils.hasPermission(res, PERMISSION.ADMINISTRATE));
        }

        serviceToken = mockJwtServiceAuthentication(RepoServiceRole.SERVICE_WRITE);
        try (MockedStatic<AuthenticationHelper> utilities = Mockito.mockStatic(AuthenticationHelper.class)) {
            setupUtilities(utilities, serviceToken);
            Assert.assertEquals(PERMISSION.WRITE, DataResourceUtils.getAccessPermission(res));
            Assert.assertTrue(DataResourceUtils.hasPermission(res, PERMISSION.READ));
            Assert.assertTrue(DataResourceUtils.hasPermission(res, PERMISSION.WRITE));
            Assert.assertFalse(DataResourceUtils.hasPermission(res, PERMISSION.ADMINISTRATE));
        }

        serviceToken = mockJwtServiceAuthentication(RepoServiceRole.SERVICE_ADMINISTRATOR);
        try (MockedStatic<AuthenticationHelper> utilities = Mockito.mockStatic(AuthenticationHelper.class)) {
            setupUtilities(utilities, serviceToken);
            Assert.assertEquals(PERMISSION.ADMINISTRATE, DataResourceUtils.getAccessPermission(res));
            Assert.assertTrue(DataResourceUtils.hasPermission(res, PERMISSION.READ));
            Assert.assertTrue(DataResourceUtils.hasPermission(res, PERMISSION.WRITE));
            Assert.assertTrue(DataResourceUtils.hasPermission(res, PERMISSION.ADMINISTRATE));
        }
    }

    private void setupUtilities(MockedStatic<AuthenticationHelper> utilities, JwtAuthenticationToken token) {
        utilities.when(AuthenticationHelper::getAuthentication).thenReturn(token);
        utilities.when(() -> AuthenticationHelper.getScopedPermission(any(String.class), any(String.class)))
                .thenCallRealMethod();
        utilities.when(() -> AuthenticationHelper.hasAuthority(any(String.class))).thenCallRealMethod();
        utilities.when(() -> AuthenticationHelper.hasIdentity(any(String.class))).thenCallRealMethod();
        utilities.when(() -> AuthenticationHelper.getPrincipal()).thenCallRealMethod();
        utilities.when(() -> AuthenticationHelper.getAuthorizationIdentities()).thenCallRealMethod();
    }

    @Test
    public void testPermissionCheckAsTemporaryUser() throws JsonProcessingException {
        DataResource res1 = DataResource.factoryNewDataResource("res1");
        res1.getAcls().add(new AclEntry("tester", PERMISSION.WRITE));
        DataResource res2 = DataResource.factoryNewDataResource("res2");
        res2.getAcls().add(new AclEntry("tester", PERMISSION.WRITE));
        DataResource res3 = DataResource.factoryNewDataResource("res3");
        res3.getAcls().add(new AclEntry("tester", PERMISSION.WRITE));
        ScopedPermission[] perms = new ScopedPermission[]{
            ScopedPermission.factoryScopedPermission("DataResource", "res1", PERMISSION.READ),
            ScopedPermission.factoryScopedPermission("DataResource", "res2", PERMISSION.WRITE),
            ScopedPermission.factoryScopedPermission("DataResource", "res3", PERMISSION.ADMINISTRATE)};

        JwtAuthenticationToken temporaryToken = mockJwtTemporaryAuthentication(perms);
        try (MockedStatic<AuthenticationHelper> utilities = Mockito.mockStatic(AuthenticationHelper.class)) {
            utilities.when(AuthenticationHelper::getAuthentication).thenReturn(temporaryToken);
            utilities.when(() -> AuthenticationHelper.getScopedPermission(any(String.class), any(String.class)))
                    .thenCallRealMethod();
            utilities.when(() -> AuthenticationHelper.hasAuthority(any(String.class))).thenCallRealMethod();
            utilities.when(() -> AuthenticationHelper.hasIdentity(any(String.class))).thenCallRealMethod();
            utilities.when(() -> AuthenticationHelper.getPrincipal()).thenCallRealMethod();
            utilities.when(() -> AuthenticationHelper.getAuthorizationIdentities()).thenCallRealMethod();
            Assert.assertEquals(PERMISSION.READ, DataResourceUtils.getAccessPermission(res1));
            Assert.assertTrue(DataResourceUtils.hasPermission(res1, PERMISSION.READ));
            Assert.assertFalse(DataResourceUtils.hasPermission(res1, PERMISSION.WRITE));
            Assert.assertFalse(DataResourceUtils.hasPermission(res1, PERMISSION.ADMINISTRATE));

            Assert.assertEquals(PERMISSION.WRITE, DataResourceUtils.getAccessPermission(res2));
            Assert.assertTrue(DataResourceUtils.hasPermission(res2, PERMISSION.READ));
            Assert.assertTrue(DataResourceUtils.hasPermission(res2, PERMISSION.WRITE));
            Assert.assertFalse(DataResourceUtils.hasPermission(res2, PERMISSION.ADMINISTRATE));

            Assert.assertEquals(PERMISSION.ADMINISTRATE, DataResourceUtils.getAccessPermission(res3));
            Assert.assertTrue(DataResourceUtils.hasPermission(res3, PERMISSION.READ));
            Assert.assertTrue(DataResourceUtils.hasPermission(res3, PERMISSION.WRITE));
            Assert.assertTrue(DataResourceUtils.hasPermission(res3, PERMISSION.ADMINISTRATE));
        }
    }

    @Test
    public void testAreAclsEqual() {
        AclEntry entry = new AclEntry("tester", PERMISSION.WRITE);
        AclEntry entry2 = new AclEntry("guest", PERMISSION.WRITE);
        AclEntry entry3 = new AclEntry("admin", PERMISSION.ADMINISTRATE);
        AclEntry entry4 = new AclEntry("guest", PERMISSION.NONE);

        //equal lists
        Assert.assertTrue(DataResourceUtils.areAclsEqual(new AclEntry[]{entry, entry2}, new AclEntry[]{entry, entry2}));
        //different order
        Assert.assertTrue(DataResourceUtils.areAclsEqual(new AclEntry[]{entry2, entry}, new AclEntry[]{entry, entry2}));
        //shorter first list
        Assert.assertFalse(DataResourceUtils.areAclsEqual(new AclEntry[]{entry}, new AclEntry[]{entry, entry2}));
        //shorter second list
        Assert.assertFalse(DataResourceUtils.areAclsEqual(new AclEntry[]{entry, entry2}, new AclEntry[]{entry}));
        //one entry per list
        Assert.assertTrue(DataResourceUtils.areAclsEqual(new AclEntry[]{entry3}, new AclEntry[]{entry3}));
        //empty first list
        Assert.assertFalse(DataResourceUtils.areAclsEqual(new AclEntry[]{}, new AclEntry[]{entry3}));
        //empty second list
        Assert.assertFalse(DataResourceUtils.areAclsEqual(new AclEntry[]{entry3}, new AclEntry[]{}));

        //changed permission
        Assert.assertFalse(DataResourceUtils.areAclsEqual(new AclEntry[]{entry, entry2}, new AclEntry[]{entry, entry4}));
    }

    @Test(expected = NullPointerException.class)
    public void testAreAclsEqualWithNullArgument() {
        DataResourceUtils.areAclsEqual(null, null);
    }

    private JwtAuthenticationToken mockJwtUserAuthentication(RepoUserRole role) {
        JwtAuthenticationToken userToken = JwtBuilder.createUserToken("tester", role).
                addSimpleClaim("firstname", "test").
                addSimpleClaim("lastname", "user").
                addSimpleClaim("email", "test@mail.org").
                addSimpleClaim("groupid", "USERS").
                getJwtAuthenticationToken(DataResourceControllerTest.KEYCLOAK_SECRET);
        return userToken;

    }

    private JwtAuthenticationToken mockJwtServiceAuthentication(RepoServiceRole role) throws JsonProcessingException {
        JwtAuthenticationToken serviceToken = JwtBuilder.createServiceToken("metadata_extractor", role).
                addSimpleClaim("groupid", "USERS").
                getJwtAuthenticationToken(DataResourceControllerTest.KEYCLOAK_SECRET);
        return serviceToken;
    }

    private JwtAuthenticationToken mockJwtTemporaryAuthentication(ScopedPermission[] perms) throws JsonProcessingException {
        JwtAuthenticationToken temporaryToken = JwtBuilder.createTemporaryToken("test@mail.org", perms).
                getJwtAuthenticationToken(DataResourceControllerTest.KEYCLOAK_SECRET);
        return temporaryToken;
    }
}
