/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.security.alias;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.util.Shell;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.EnumSet;

/**
 * CredentialProvider based on Java's KeyStore file format. The file may be
 * stored only on the local filesystem using the following name mangling:
 * localjceks://file/home/larry/creds.jceks -> file:///home/larry/creds.jceks
 */
@InterfaceAudience.Private
public final class LocalJavaKeyStoreProvider extends
    AbstractJavaKeyStoreProvider {
    public static final String SCHEME_NAME = "localjceks";
    private File file;
    private Set<PosixFilePermission> permissions;

    private LocalJavaKeyStoreProvider(URI uri, Configuration conf)
    throws IOException {
        super(uri, conf);
    }

    @Override
    protected String getSchemeName() {
        return SCHEME_NAME;
    }

    @Override
    protected OutputStream getOutputStreamForKeystore() throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        return out;
    }

    @Override
    protected boolean keystoreExists() throws IOException {
        return file.exists();
    }

    @Override
    protected InputStream getInputStreamForFile() throws IOException {
        FileInputStream is = new FileInputStream(file);
        return is;
    }

    @Override
    protected void createPermissions(String perms) throws IOException {
        int mode = 700;
        try {
            mode = Integer.parseInt(perms, 8);
        } catch (NumberFormatException nfe) {
            throw new IOException("Invalid permissions mode provided while "
                                  + "trying to createPermissions", nfe);
        }
        permissions = modeToPosixFilePermission(mode);
    }

    @Override
    protected void stashOriginalFilePermissions() throws IOException {
        // save off permissions in case we need to
        // rewrite the keystore in flush()
        if (!Shell.WINDOWS) {
            Path path = Paths.get(file.getCanonicalPath());
            permissions = Files.getPosixFilePermissions(path);
        } else {
            // On Windows, the JDK does not support the POSIX file permission APIs.
            // Instead, we can do a winutils call and translate.
            String[] cmd = Shell.getGetPermissionCommand();
            String[] args = new String[cmd.length + 1];
            System.arraycopy(cmd, 0, args, 0, cmd.length);
            args[cmd.length] = file.getCanonicalPath();
            String out = Shell.execCommand(args);
            StringTokenizer t = new StringTokenizer(out, Shell.TOKEN_SEPARATOR_REGEX);
            // The winutils output consists of 10 characters because of the leading
            // directory indicator, i.e. "drwx------".  The JDK parsing method expects
            // a 9-character string, so remove the leading character.
            String permString = t.nextToken().substring(1);
            permissions = PosixFilePermissions.fromString(permString);
        }
    }

    @Override
    protected void initFileSystem(URI uri, Configuration conf)
    throws IOException {
        super.initFileSystem(uri, conf);
        try {
            file = new File(new URI(getPath().toString()));
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void flush() throws IOException {
        super.flush();
        if (!Shell.WINDOWS) {
            Files.setPosixFilePermissions(Paths.get(file.getCanonicalPath()),
                                          permissions);
        } else {
            // FsPermission expects a 10-character string because of the leading
            // directory indicator, i.e. "drwx------". The JDK toString method returns
            // a 9-character string, so prepend a leading character.
            FsPermission fsPermission = FsPermission.valueOf(
                                            "-" + PosixFilePermissions.toString(permissions));
            FileUtil.setPermission(file, fsPermission);
        }
    }

    /**
     * The factory to create JksProviders, which is used by the ServiceLoader.
     */
    public static class Factory extends CredentialProviderFactory {
        @Override
        public CredentialProvider createProvider(URI providerName,
                Configuration conf) throws IOException {
            if (SCHEME_NAME.equals(providerName.getScheme())) {
                return new LocalJavaKeyStoreProvider(providerName, conf);
            }
            return null;
        }
    }

    private static Set<PosixFilePermission> modeToPosixFilePermission(
        int mode) {
        Set<PosixFilePermission> perms = EnumSet.noneOf(PosixFilePermission.class);
        if ((mode & 0001) != 0) {
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
        }
        if ((mode & 0002) != 0) {
            perms.add(PosixFilePermission.OTHERS_WRITE);
        }
        if ((mode & 0004) != 0) {
            perms.add(PosixFilePermission.OTHERS_READ);
        }
        if ((mode & 0010) != 0) {
            perms.add(PosixFilePermission.GROUP_EXECUTE);
        }
        if ((mode & 0020) != 0) {
            perms.add(PosixFilePermission.GROUP_WRITE);
        }
        if ((mode & 0040) != 0) {
            perms.add(PosixFilePermission.GROUP_READ);
        }
        if ((mode & 0100) != 0) {
            perms.add(PosixFilePermission.OWNER_EXECUTE);
        }
        if ((mode & 0200) != 0) {
            perms.add(PosixFilePermission.OWNER_WRITE);
        }
        if ((mode & 0400) != 0) {
            perms.add(PosixFilePermission.OWNER_READ);
        }
        return perms;
    }
}
