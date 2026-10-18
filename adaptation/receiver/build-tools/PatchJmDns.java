import java.nio.file.*;
import java.util.jar.*;
import java.io.*;
import org.jetbrains.org.objectweb.asm.*;

/** Replace only newer charset field references; preserve all DNS wire logic. */
public final class PatchJmDns {
    public static void main(String[] args) throws Exception {
        int[] patched = {0};
        try (JarInputStream input = new JarInputStream(Files.newInputStream(Paths.get(args[0])));
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(Paths.get(args[1])))) {
            JarEntry entry;
            while ((entry = input.getNextJarEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA"))) continue;
                byte[] bytes = input.readAllBytes();
                if (name.endsWith(".class")) {
                    ClassReader reader = new ClassReader(bytes);
                    ClassWriter writer = new ClassWriter(0);
                    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                            return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, method, descriptor, signature, exceptions)) {
                                @Override public void visitFieldInsn(int opcode, String owner, String field, String descriptor) {
                                    if (owner.equals("java/nio/charset/StandardCharsets")) { owner = "local/airuize/receiver/LegacyCharsetFields"; patched[0]++; }
                                    super.visitFieldInsn(opcode, owner, field, descriptor);
                                }
                            };
                        }
                    }, 0);
                    bytes = writer.toByteArray();
                }
                output.putNextEntry(new JarEntry(name)); output.write(bytes); output.closeEntry();
            }
        }
        if (patched[0] == 0) throw new IllegalStateException("Expected newer charset references");
        System.out.println("JmDNS API18 charset references patched: " + patched[0]);
    }
}
