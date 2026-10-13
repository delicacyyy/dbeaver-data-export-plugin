package com.janetfilter.dbeaver.export;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Authorization gate for the new connection-administration routes. */
final class CreationAccess {
    private static final String TOKEN_FILE_PROPERTY = "janf.dataExport.authTokenFile";
    private static final int MAX_TOKEN_FILE_BYTES = 4096;
    private static final int MAX_AUTHORIZATION_LENGTH = 8192;
    private static final String BEARER_PREFIX = "Bearer ";
    private static final Set<PosixFilePermission> NON_OWNER_PERMISSIONS = EnumSet.of(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE);

    private final byte[] token;

    private CreationAccess(byte[] token) {
        this.token = token;
    }

    static CreationAccess load() {
        try {
            String configuredPath = System.getProperty(TOKEN_FILE_PROPERTY);
            if (configuredPath == null || configuredPath.trim().isEmpty()) {
                return disabled();
            }
            Path path = Paths.get(configuredPath);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !securePermissions(path)) {
                return disabled();
            }
            long size = Files.size(path);
            if (size <= 0 || size > MAX_TOKEN_FILE_BYTES) {
                return disabled();
            }
            byte[] contents = Files.readAllBytes(path);
            if (contents.length == 0 || contents.length > MAX_TOKEN_FILE_BYTES) {
                return disabled();
            }
            String tokenText = decodeToken(contents);
            if (!validToken(tokenText)) {
                return disabled();
            }
            return new CreationAccess(tokenText.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException | RuntimeException e) {
            return disabled();
        }
    }

    void require(String authorization, String origin) {
        if (token == null) {
            throw new DataExportPlugin.HttpException(
                    503, "connection administration authorization is unavailable");
        }
        if (origin != null) {
            throw new DataExportPlugin.HttpException(403, "Origin is not allowed");
        }
        if (authorization == null || authorization.length() > MAX_AUTHORIZATION_LENGTH
                || authorization.length() <= BEARER_PREFIX.length()
                || !authorization.regionMatches(
                        true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw unauthorized();
        }
        String supplied = authorization.substring(BEARER_PREFIX.length());
        if (!validToken(supplied)) {
            throw unauthorized();
        }
        byte[] presented = supplied.getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(token, presented)) {
            throw unauthorized();
        }
    }

    private static CreationAccess disabled() {
        return new CreationAccess(null);
    }

    private static DataExportPlugin.HttpException unauthorized() {
        return new DataExportPlugin.HttpException(401, "valid bearer token required");
    }

    private static boolean securePermissions(Path path) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Set<PosixFilePermission> permissions = posix.readAttributes().permissions();
            if (!permissions.contains(PosixFilePermission.OWNER_READ)) {
                return false;
            }
            for (PosixFilePermission permission : NON_OWNER_PERMISSIONS) {
                if (permissions.contains(permission)) {
                    return false;
                }
            }
            return ownerMatchesCurrentUser(
                    Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).getName());
        }

        AclFileAttributeView aclView = Files.getFileAttributeView(
                path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (aclView == null) {
            return false;
        }
        String owner = aclView.getOwner().getName();
        if (!ownerMatchesCurrentUser(owner)) {
            return false;
        }
        boolean ownerCanRead = false;
        List<AclEntry> entries = aclView.getAcl();
        for (AclEntry entry : entries) {
            if (entry.type() != AclEntryType.ALLOW) {
                continue;
            }
            String principal = entry.principal().getName();
            Set<AclEntryPermission> permissions = entry.permissions();
            if (samePrincipal(principal, owner)) {
                ownerCanRead |= permissions.contains(AclEntryPermission.READ_DATA);
            } else if (!isTrustedWindowsPrincipal(principal)) {
                return false;
            }
        }
        return ownerCanRead;
    }

    private static boolean ownerMatchesCurrentUser(String owner) {
        String user = System.getProperty("user.name");
        if (owner == null || user == null || user.isEmpty()) {
            return false;
        }
        if (samePrincipal(owner, user)) {
            return true;
        }
        int separator = owner.lastIndexOf('\\');
        if (separator >= 0 && samePrincipal(owner.substring(separator + 1), user)) {
            return true;
        }
        int domain = owner.indexOf('@');
        return domain > 0 && samePrincipal(owner.substring(0, domain), user);
    }

    private static boolean samePrincipal(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    static boolean isTrustedWindowsPrincipal(String principal) {
        return "NT AUTHORITY\\SYSTEM".equalsIgnoreCase(principal)
                || "BUILTIN\\Administrators".equalsIgnoreCase(principal);
    }

    private static String decodeToken(byte[] contents) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        String tokenText = decoder.decode(ByteBuffer.wrap(contents)).toString();
        if (tokenText.endsWith("\n")) {
            tokenText = tokenText.substring(0, tokenText.length() - 1);
            if (tokenText.endsWith("\r")) {
                tokenText = tokenText.substring(0, tokenText.length() - 1);
            }
        }
        return tokenText;
    }

    private static boolean validToken(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_TOKEN_FILE_BYTES) {
            return false;
        }
        boolean padding = false;
        boolean hasPayload = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '=') {
                padding = true;
            } else if (isBearerTokenCharacter(ch) && !padding) {
                hasPayload = true;
            } else {
                return false;
            }
        }
        return hasPayload;
    }

    private static boolean isBearerTokenCharacter(char ch) {
        return (ch >= 'A' && ch <= 'Z')
                || (ch >= 'a' && ch <= 'z')
                || (ch >= '0' && ch <= '9')
                || ch == '-' || ch == '.' || ch == '_' || ch == '~'
                || ch == '+' || ch == '/';
    }
}
