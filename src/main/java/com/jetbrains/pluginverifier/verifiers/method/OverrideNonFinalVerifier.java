package com.jetbrains.pluginverifier.verifiers.method;

import com.jetbrains.pluginverifier.ErrorRegister;
import com.jetbrains.pluginverifier.pool.Resolver;
import com.jetbrains.pluginverifier.verifiers.util.VerifierUtil;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * @author Dennis.Ushakov
 */
public class OverrideNonFinalVerifier implements MethodVerifier {
  public void verify(final ClassNode clazz, final MethodNode method, final Resolver resolver, final ErrorRegister register) {
    if ((method.access & Opcodes.ACC_PRIVATE) != 0) return;
    final String superClass = clazz.superName;
    final MethodNode superMethod = resolver.findMethod(superClass, method.name, method.desc);
    if (superMethod == null) return;
    if (VerifierUtil.isFinal(superMethod) && !VerifierUtil.isAbstract(superMethod)) {
      register.registerError(clazz.name + "." + method.name + " " + method.desc, "overriding final method");
    }
  }
}
