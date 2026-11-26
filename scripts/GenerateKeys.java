import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.util.Base64;

class GenerateKeys {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        Files.createDirectories(directory);
        Path privateKey = directory.resolve("private.pem");
        Path publicKey = directory.resolve("public.pem");
        Path lock = Files.createFile(directory.resolve(".generating"));
        try {
            if (Files.exists(privateKey) && Files.exists(publicKey)) {
                System.out.println("Kestrel RSA signing keys already exist.");
                return;
            }
            if (Files.exists(privateKey) || Files.exists(publicKey)) {
                throw new IOException("The key directory contains an incomplete pair. Choose a new directory.");
            }

            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072);
            var keys = generator.generateKeyPair();

            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                Files.createFile(privateKey, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(privateKey);
            }

            try {
                Files.writeString(privateKey, pem("PRIVATE KEY", keys.getPrivate().getEncoded()));
                Files.writeString(publicKey, pem("PUBLIC KEY", keys.getPublic().getEncoded()),
                        java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
            } catch (IOException incomplete) {
                Files.deleteIfExists(privateKey);
                throw incomplete;
            }
            System.out.println("Created Kestrel RSA signing keys in " + directory.toAbsolutePath());
        } finally {
            Files.delete(lock);
        }
    }

    private static String pem(String label, byte[] encoded) {
        String base64 = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
        return "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n";
    }
}
