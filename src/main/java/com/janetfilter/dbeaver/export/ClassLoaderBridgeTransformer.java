package com.janetfilter.dbeaver.export;

import com.janetfilter.core.plugin.MyTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Injects a classloader bridge call at the end of
 * {@code LicenseServiceEmbedded.<init>()}.
 *
 * <p>The injected bytecode stores DBeaver's OSGi classloader
 * into a static field on {@link DataExportPlugin}, enabling
 * reflection-based access to DBeaver's runtime objects.</p>
 *
 * <p>Injected code (at end of constructor, before return):
 * <pre>
 *   aload_0
 *   invokevirtual Object.getClass()
 *   invokevirtual Class.getClassLoader()
 *   invokestatic DataExportPlugin.setDBeaverClassLoader(ClassLoader)V
 * </pre>
 * All types are JDK classes — no DBeaver types in the
 * replacement bytecode.</p>
 */
public final class ClassLoaderBridgeTransformer
        implements MyTransformer {

    private static final String TARGET_CLASS =
            "com/dbeaver/model/license/embedded/LicenseServiceEmbedded";

    private static final String TARGET_METHOD = "<init>";

    // Constructor with (boolean, LMLicenseValidator)
    private static final String TARGET_DESC =
            "(ZLcom/dbeaver/lm/api/LMLicenseValidator;)V";

    // Our plugin class (must be bootstrap-visible)
    private static final String PLUGIN_CLASS =
            "com/janetfilter/dbeaver/export/DataExportPlugin";

    private static final String BRIDGE_METHOD = "setDBeaverClassLoader";
    private static final String BRIDGE_DESC =
            "(Ljava/lang/ClassLoader;)V";

    @Override
    public String getHookClassName() {
        return TARGET_CLASS;
    }

    @Override
    public byte[] transform(String className,
                            byte[] classBytes,
                            int order) throws Exception {

        ClassReader classReader = new ClassReader(classBytes);
        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);

        classReader.accept(new ClassVisitor(Opcodes.ASM9, classWriter) {
            @Override
            public MethodVisitor visitMethod(int access,
                                             String name,
                                             String descriptor,
                                             String signature,
                                             String[] exceptions) {

                MethodVisitor methodVisitor = super.visitMethod(
                        access, name, descriptor, signature, exceptions);

                // Intercept the target constructor
                if (TARGET_METHOD.equals(name)
                        && TARGET_DESC.equals(descriptor)) {
                    return new MethodVisitor(Opcodes.ASM9, methodVisitor) {
                        @Override
                        public void visitInsn(int opcode) {
                            // Inject before every RETURN (but not
                            // before the super() call's implicit return).
                            // We inject after the constructor body is
                            // complete, before the final RETURN.
                            if (opcode == Opcodes.RETURN) {
                                injectBridge();
                            }
                            super.visitInsn(opcode);
                        }

                        private void injectBridge() {
                            // aload_0 → this
                            methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                            // this.getClass()
                            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                    "java/lang/Object",
                                    "getClass",
                                    "()Ljava/lang/Class;",
                                    false);
                            // Class.getClassLoader()
                            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                    "java/lang/Class",
                                    "getClassLoader",
                                    "()Ljava/lang/ClassLoader;",
                                    false);
                            // DataExportPlugin.setDBeaverClassLoader(cl)
                            methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    PLUGIN_CLASS,
                                    BRIDGE_METHOD,
                                    BRIDGE_DESC,
                                    false);
                        }
                    };
                }
                return methodVisitor;
            }
        }, 0);

        return classWriter.toByteArray();
    }
}
