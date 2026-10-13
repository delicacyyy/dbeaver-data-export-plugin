package com.janetfilter.dbeaver.export;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.Assume;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;

public class CreationAccessTest {
    private static final String PROPERTY = "janf.dataExport.authTokenFile";
    private static final String TOKEN = "local-test-token_42";

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private String previousProperty;
    private boolean propertyWasSet;

    @Before
    public void rememberProperty() {
        propertyWasSet = System.getProperties().containsKey(PROPERTY);
        previousProperty = System.getProperty(PROPERTY);
        System.clearProperty(PROPERTY);
    }

    @After
    public void restoreProperty() {
        if (propertyWasSet) {
            System.setProperty(PROPERTY, previousProperty);
        } else {
            System.clearProperty(PROPERTY);
        }
    }

    @Test
    public void missingOrEmptyTokenFileDisablesAdministration() throws Exception {
        assertGateStatus(CreationAccess.load(), 503, null, null);

        Path missing = temporaryFolder.getRoot().toPath().resolve("missing-token-file");
        System.setProperty(PROPERTY, missing.toString());
        assertGateStatus(CreationAccess.load(), 503, null, null);

        Path empty = temporaryFolder.newFile().toPath();
        ownerOnlyPermissions(empty);
        System.setProperty(PROPERTY, empty.toString());
        assertGateStatus(CreationAccess.load(), 503, null, null);
    }

    @Test
    public void rejectsEveryBrowserOrigin() throws Exception {
        CreationAccess access = accessWithToken(TOKEN + "\n");

        assertGateStatus(access, 403, "Bearer " + TOKEN, "https://example.invalid");
        assertGateStatus(access, 403, "Bearer " + TOKEN, "");
    }

    @Test
    public void missingMalformedWrongAndOversizedHeadersAreUnauthorized() throws Exception {
        CreationAccess access = accessWithToken(TOKEN);
        assertGateStatus(access, 401, null, null);
        assertGateStatus(access, 401, "Bearer ", null);
        assertGateStatus(access, 401, "Basic " + TOKEN, null);
        assertGateStatus(access, 401, "Bearer different-token", null);
        assertGateStatus(access, 401, "Bearer " + repeat('a', 8200), null);
    }

    @Test
    public void insecurePosixPermissionsDisableAdministration() throws Exception {
        Path file = temporaryFolder.newFile().toPath();
        Files.write(file, TOKEN.getBytes(StandardCharsets.UTF_8));
        PosixFileAttributeView view = Files.getFileAttributeView(
                file, PosixFileAttributeView.class);
        Assume.assumeNotNull(view);
        Files.setPosixFilePermissions(file, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.GROUP_READ));
        System.setProperty(PROPERTY, file.toString());

        assertGateStatus(CreationAccess.load(), 503, null, null);
    }

    @Test
    public void onlyCanonicalWindowsSecurityPrincipalsAreTrusted() {
        assertTrue(CreationAccess.isTrustedWindowsPrincipal("NT AUTHORITY\\SYSTEM"));
        assertTrue(CreationAccess.isTrustedWindowsPrincipal("BUILTIN\\Administrators"));
        assertFalse(CreationAccess.isTrustedWindowsPrincipal("CORP\\SYSTEM"));
        assertFalse(CreationAccess.isTrustedWindowsPrincipal("CORP\\Administrators"));
        assertFalse(CreationAccess.isTrustedWindowsPrincipal("SYSTEM"));
        assertFalse(CreationAccess.isTrustedWindowsPrincipal("Administrators"));
    }

    @Test
    public void tokenFileMustBeReadableByOwnerAndSingleLine() throws Exception {
        Path file = temporaryFolder.newFile().toPath();
        Files.write(file, (TOKEN + "\nsecond-line").getBytes(StandardCharsets.UTF_8));
        ownerOnlyPermissions(file);
        System.setProperty(PROPERTY, file.toString());
        assertGateStatus(CreationAccess.load(), 503, null, null);

        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(file, EnumSet.of(PosixFilePermission.OWNER_WRITE));
            Files.write(file, TOKEN.getBytes(StandardCharsets.UTF_8));
            assertGateStatus(CreationAccess.load(), 503, null, null);
        }
    }

    private CreationAccess accessWithToken(String contents) throws Exception {
        Path file = temporaryFolder.newFile().toPath();
        Files.write(file, contents.getBytes(StandardCharsets.UTF_8));
        ownerOnlyPermissions(file);
        System.setProperty(PROPERTY, file.toString());
        return CreationAccess.load();
    }

    private static void ownerOnlyPermissions(Path file) throws IOException {
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(file, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } else {
            java.nio.file.attribute.AclFileAttributeView acl =
                    Files.getFileAttributeView(file,
                            java.nio.file.attribute.AclFileAttributeView.class);
            if (acl == null) throw new IOException("permission inspection unavailable");
            acl.setAcl(java.util.Collections.singletonList(
                    java.nio.file.attribute.AclEntry.newBuilder()
                            .setType(java.nio.file.attribute.AclEntryType.ALLOW)
                            .setPrincipal(Files.getOwner(file))
                            .setPermissions(EnumSet.allOf(
                                    java.nio.file.attribute.AclEntryPermission.class))
                            .build()));
        }
    }

    private static void assertGateStatus(
            CreationAccess access, int expected, String authorization, String origin) {
        try {
            access.require(authorization, origin);
            fail("unauthorized request was accepted");
        } catch (DataExportPlugin.HttpException exception) {
            assertEquals(expected, exception.status);
            assertFalse(exception.getMessage().contains(TOKEN));
            if (authorization != null) {
                assertFalse(exception.getMessage().contains(authorization));
            }
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            result.append(value);
        }
        return result.toString();
    }
}
