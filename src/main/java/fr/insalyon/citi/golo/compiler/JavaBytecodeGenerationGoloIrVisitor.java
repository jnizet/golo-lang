/*
 * Copyright (c) 2012-2015 Institut National des Sciences Appliquées de Lyon (INSA-Lyon)
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package fr.insalyon.citi.golo.compiler;

import fr.insalyon.citi.golo.compiler.ir.*;
import fr.insalyon.citi.golo.compiler.parser.GoloParser;
import fr.insalyon.citi.golo.runtime.OperatorType;
import gololang.FunctionReference;
import org.objectweb.asm.*;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.*;

import static fr.insalyon.citi.golo.compiler.JavaBytecodeUtils.*;
import static fr.insalyon.citi.golo.compiler.ir.GoloFunction.Visibility.PUBLIC;
import static fr.insalyon.citi.golo.runtime.OperatorType.*;
import static java.lang.invoke.MethodType.genericMethodType;
import static java.lang.invoke.MethodType.methodType;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Opcodes.*;

class JavaBytecodeGenerationGoloIrVisitor implements GoloIrVisitor {

  private static final String JOBJECT = "java/lang/Object";
  private static final String TOBJECT = "Ljava/lang/Object;";
  private static final Handle FUNCTION_INVOCATION_HANDLE;
  private static final Handle OPERATOR_HANDLE;
  private static final Handle METHOD_INVOCATION_HANDLE;
  private static final Handle CLASSREF_HANDLE;
  private static final Handle CLOSUREREF_HANDLE;
  private static final Handle CLOSURE_INVOCATION_HANDLE;

  static {
    String bootstrapOwner = "fr/insalyon/citi/golo/runtime/FunctionCallSupport";
    String bootstrapMethod = "bootstrap";
    String description = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;";
    FUNCTION_INVOCATION_HANDLE = new Handle(H_INVOKESTATIC, bootstrapOwner, bootstrapMethod, description);

    bootstrapOwner = "fr/insalyon/citi/golo/runtime/OperatorSupport";
    bootstrapMethod = "bootstrap";
    description = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;I)Ljava/lang/invoke/CallSite;";
    OPERATOR_HANDLE = new Handle(H_INVOKESTATIC, bootstrapOwner, bootstrapMethod, description);

    bootstrapOwner = "fr/insalyon/citi/golo/runtime/MethodInvocationSupport";
    bootstrapMethod = "bootstrap";
    description = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;";
    METHOD_INVOCATION_HANDLE = new Handle(H_INVOKESTATIC, bootstrapOwner, bootstrapMethod, description);

    bootstrapOwner = "fr/insalyon/citi/golo/runtime/ClassReferenceSupport";
    bootstrapMethod = "bootstrap";
    description = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";
    CLASSREF_HANDLE = new Handle(H_INVOKESTATIC, bootstrapOwner, bootstrapMethod, description);

    bootstrapOwner = "fr/insalyon/citi/golo/runtime/ClosureReferenceSupport";
    bootstrapMethod = "bootstrap";
    description = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;II)Ljava/lang/invoke/CallSite;";
    CLOSUREREF_HANDLE = new Handle(H_INVOKESTATIC, bootstrapOwner, bootstrapMethod, description);

    bootstrapOwner = "fr/insalyon/citi/golo/runtime/ClosureCallSupport";
    bootstrapMethod = "bootstrap";
    description = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;";
    CLOSURE_INVOCATION_HANDLE = new Handle(H_INVOKESTATIC, bootstrapOwner, bootstrapMethod, description);
  }

  private ClassWriter classWriter;
  private String klass;
  private String jvmKlass;
  private MethodVisitor methodVisitor;
  private List<CodeGenerationResult> generationResults;
  private String sourceFilename;
  private Context context;

  private static class Context {
    private final Deque<ReferenceTable> referenceTableStack = new LinkedList<>();
    private final Map<LoopStatement, Label> loopStartMap = new HashMap<>();
    private final Map<LoopStatement, Label> loopEndMap = new HashMap<>();
  }

  public List<CodeGenerationResult> generateBytecode(GoloModule module, String sourceFilename) {
    this.sourceFilename = sourceFilename;
    this.classWriter = new ClassWriter(COMPUTE_FRAMES | COMPUTE_MAXS);
    this.generationResults = new LinkedList<>();
    this.context = new Context();
    module.accept(this);
    this.generationResults.add(new CodeGenerationResult(classWriter.toByteArray(), module.getPackageAndClass()));
    return this.generationResults;
  }

  @Override
  public void visitModule(GoloModule module) {
    classWriter.visit(V1_8, ACC_PUBLIC | ACC_SUPER, module.getPackageAndClass().toJVMType(), null, JOBJECT, null);
    classWriter.visitSource(sourceFilename, null);
    writeImportMetaData(module.getImports());
    klass = module.getPackageAndClass().toString();
    jvmKlass = module.getPackageAndClass().toJVMType();
    for (GoloFunction function : module.getFunctions()) {
      function.accept(this);
    }
    generateAugmentationsBytecode(module, module.getAugmentations());
    generateAugmentationsBytecode(module, module.getNamedAugmentations());
    if (module.getStructs().size() > 0) {
      JavaBytecodeStructGenerator structGenerator = new JavaBytecodeStructGenerator();
      for (Struct struct : module.getStructs()) {
        generationResults.add(structGenerator.compile(struct, sourceFilename));
      }
    }
    if (!module.getUnions().isEmpty()) {
      JavaBytecodeUnionGenerator unionGenerator = new JavaBytecodeUnionGenerator();
      for (Union e : module.getUnions()) {
        generationResults.addAll(unionGenerator.compile(e, sourceFilename));
      }
    }
    for (LocalReference moduleState : module.getModuleState()) {
      writeModuleState(moduleState);
    }
    writeAugmentsMetaData(module.getAugmentations().keySet());
    writeAugmentationApplicationsMetaData(module.getAugmentationApplications());
    classWriter.visitEnd();
  }

  private void writeModuleState(LocalReference moduleState) {
    String name = moduleState.getName();
    classWriter.visitField(ACC_PRIVATE | ACC_STATIC, name, "Ljava/lang/Object;", null, null).visitEnd();

    MethodVisitor mv = classWriter.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, name, "()Ljava/lang/Object;", null, null);
    mv.visitCode();
    mv.visitFieldInsn(GETSTATIC, jvmKlass, name, "Ljava/lang/Object;");
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();

    mv = classWriter.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, name, "(Ljava/lang/Object;)V", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitFieldInsn(PUTSTATIC, jvmKlass, name, "Ljava/lang/Object;");
    mv.visitInsn(RETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  private void writeMetaData(String name, String[] data) {
    methodVisitor = classWriter.visitMethod(
        ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC,
        "$" + name,
        "()[Ljava/lang/String;",
        null, null);
    methodVisitor.visitCode();
    loadInteger(methodVisitor, data.length);
    methodVisitor.visitTypeInsn(ANEWARRAY, "java/lang/String");
    for (int i = 0; i < data.length; i++) {
      methodVisitor.visitInsn(DUP);
      loadInteger(methodVisitor, i);
      methodVisitor.visitLdcInsn(data[i]);
      methodVisitor.visitInsn(AASTORE);
    }
    methodVisitor.visitInsn(ARETURN);
    methodVisitor.visitMaxs(0, 0);
    methodVisitor.visitEnd();
  }

  private void writeAugmentationApplicationsMetaData(Map<String, Set<String>> applications) {
    /* create a metadata method that given a target class name hashcode
     * returns a String array containing the names of applied
     * augmentations
     */

    int applicationsSize = applications.size();
    List<String> applicationNames = new ArrayList<>(applications.keySet());
    writeMetaData("augmentationApplications", applicationNames.toArray(new String[applicationsSize]));

    Label defaultLabel = new Label();
    Label[] labels = new Label[applicationsSize];
    int[] keys = new int[applicationsSize];
    String[][] namesArrays = new String[applicationsSize][];
    // cases of the switch statement MUST be sorted
    Collections.sort(applicationNames, new Comparator<String>(){
      @Override
      public int compare(String o1, String o2) {
        return Integer.compare(o1.hashCode(), o2.hashCode());
      }
    });
    int i = 0;
    for (String applicationName : applicationNames) {
      labels[i] = new Label();
      keys[i] = applicationName.hashCode();
      namesArrays[i] = applications.get(applicationName).toArray(new String[applications.get(applicationName).size()]);
      i++;
    }
    methodVisitor = classWriter.visitMethod(
        ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC,
        "$augmentationApplications",
        "(I)[Ljava/lang/String;",
        null, null);
    methodVisitor.visitCode();
    methodVisitor.visitVarInsn(ILOAD, 0);
    methodVisitor.visitLookupSwitchInsn(defaultLabel, keys, labels);
    for (i=0; i < applicationsSize; i++) {
      methodVisitor.visitLabel(labels[i]);
      loadInteger(methodVisitor, namesArrays[i].length);
      methodVisitor.visitTypeInsn(ANEWARRAY, "java/lang/String");
      for (int j = 0; j < namesArrays[i].length; j++) {
        methodVisitor.visitInsn(DUP);
        loadInteger(methodVisitor, j);
        methodVisitor.visitLdcInsn(namesArrays[i][j]);
        methodVisitor.visitInsn(AASTORE);
      }
      methodVisitor.visitInsn(ARETURN);
    }
    methodVisitor.visitLabel(defaultLabel);
    loadInteger(methodVisitor, 0);
    methodVisitor.visitTypeInsn(ANEWARRAY, "java/lang/String");
    methodVisitor.visitInsn(ARETURN);
    methodVisitor.visitMaxs(0, 0);
    methodVisitor.visitEnd();
  }

  private void writeImportMetaData(Set<ModuleImport> imports) {
    String[] importsArray = new String[imports.size()];
    int i = 0;
    for (ModuleImport imp : imports) {
      importsArray[i] = imp.getPackageAndClass().toString();
      i++;
    }
    writeMetaData("imports", importsArray);
  }

  private void writeAugmentsMetaData(Set<String> augmentations) {
    String[] augmentArray = augmentations.toArray(new String[augmentations.size()]);
    writeMetaData("augmentations", augmentArray);
  }

  private void generateAugmentationsBytecode(GoloModule module, Map<String, Set<GoloFunction>> augmentations) {
    for (Map.Entry<String, Set<GoloFunction>> entry : augmentations.entrySet()) {
      generateAugmentationBytecode(module, entry.getKey(), entry.getValue());
    }
  }

  private void generateAugmentationBytecode(GoloModule module, String target, Set<GoloFunction> functions) {
    ClassWriter mainClassWriter = classWriter;
    String mangledClass = target.replace('.', '$');
    PackageAndClass packageAndClass = new PackageAndClass(
        module.getPackageAndClass().packageName(),
        module.getPackageAndClass().className() + "$" + mangledClass);
    String augmentationClassInternalName = packageAndClass.toJVMType();
    String outerName = module.getPackageAndClass().toJVMType();

    mainClassWriter.visitInnerClass(
        augmentationClassInternalName,
        outerName,
        mangledClass,
        ACC_PUBLIC | ACC_STATIC);

    classWriter = new ClassWriter(COMPUTE_FRAMES | COMPUTE_MAXS);
    classWriter.visit(V1_8, ACC_PUBLIC | ACC_SUPER, augmentationClassInternalName, null, JOBJECT, null);
    classWriter.visitSource(sourceFilename, null);
    classWriter.visitOuterClass(outerName, null, null);

    for (GoloFunction function : functions) {
      function.accept(this);
    }

    Set<ModuleImport> imports = new HashSet<>(module.getImports());
    imports.add(new ModuleImport(module.getPackageAndClass()));
    writeImportMetaData(imports);

    classWriter.visitEnd();
    generationResults.add(new CodeGenerationResult(classWriter.toByteArray(), packageAndClass));
    classWriter = mainClassWriter;
  }

  @Override
  public void visitFunction(GoloFunction function) {
    int accessFlags = (function.getVisibility() == PUBLIC) ? ACC_PUBLIC : ACC_PRIVATE;
    String signature;
    if (function.isMain()) {
      signature = "([Ljava/lang/String;)V";
    } else if (function.isVarargs()) {
      accessFlags = accessFlags | ACC_VARARGS;
      signature = goloVarargsFunctionSignature(function.getArity());
    } else if (function.isModuleInit()) {
      signature = "()V";
    } else {
      signature = goloFunctionSignature(function.getArity());
    }
    if (function.isSynthetic() || function.isDecorator()) {
      accessFlags = accessFlags | ACC_SYNTHETIC;
    }
    methodVisitor = classWriter.visitMethod(
        accessFlags | ACC_STATIC,
        function.getName(),
        signature,
        null, null);
    if (function.isDecorated()) {
      AnnotationVisitor annotation = methodVisitor.visitAnnotation("Lgololang/annotations/DecoratedBy;", true);
      annotation.visit("value", function.getDecoratorRef());
      annotation.visitEnd();
    }
    for(String parameter: function.getParameterNames()) {
      methodVisitor.visitParameter(parameter, ACC_FINAL);
    }
    methodVisitor.visitCode();
    visitLine(function, methodVisitor);
    function.getBlock().accept(this);
    if (function.isModuleInit()) {
      methodVisitor.visitInsn(RETURN);
    }
    methodVisitor.visitMaxs(0, 0);
    methodVisitor.visitEnd();
  }

  @Override
  public void visitDecorator(Decorator decorator) {
    decorator.getExpressionStatement().accept(this);
  }

  private String goloFunctionSignature(int arity) {
    return MethodType.genericMethodType(arity).toMethodDescriptorString();
  }

  private String goloVarargsFunctionSignature(int arity) {
    return MethodType.genericMethodType(arity - 1, true).toMethodDescriptorString();
  }

  @Override
  public void visitBlock(Block block) {
    ReferenceTable referenceTable = block.getReferenceTable();
    context.referenceTableStack.push(referenceTable);
    Label blockStart = new Label();
    Label blockEnd = new Label();
    methodVisitor.visitLabel(blockStart);
    for (GoloStatement statement : block.getStatements()) {
      visitLine(statement, methodVisitor);
      statement.accept(this);
      insertMissingPop(statement);
    }
    methodVisitor.visitLabel(blockEnd);
    for (LocalReference localReference : referenceTable.ownedReferences()) {
      if (localReference.isModuleState()) {
        continue;
      }
      methodVisitor.visitLocalVariable(localReference.getName(), TOBJECT, null,
          blockStart, blockEnd, localReference.getIndex());
    }
    context.referenceTableStack.pop();
  }

  private void insertMissingPop(GoloStatement statement) {
    Class<? extends GoloStatement> statementClass = statement.getClass();
    if (statementClass == FunctionInvocation.class) {
      methodVisitor.visitInsn(POP);
    } else if (statementClass == BinaryOperation.class) {
      BinaryOperation operation = (BinaryOperation) statement;
      if (isMethodCall(operation)) {
        methodVisitor.visitInsn(POP);
      }
    }
  }

  private boolean isMethodCall(BinaryOperation operation) {
    return operation.getType() == METHOD_CALL
            || operation.getType() == ELVIS_METHOD_CALL
            || operation.getType() == ANON_CALL;
  }

  @Override
  public void visitConstantStatement(ConstantStatement constantStatement) {
    Object value = constantStatement.getValue();
    if (value == null) {
      methodVisitor.visitInsn(ACONST_NULL);
      return;
    }
    if (value instanceof Integer) {
      int i = (Integer) value;
      loadInteger(methodVisitor, i);
      methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
      return;
    }
    if (value instanceof Long) {
      long l = (Long) value;
      loadLong(methodVisitor, l);
      methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
      return;
    }
    if (value instanceof Boolean) {
      boolean b = (Boolean) value;
      loadInteger(methodVisitor, b ? 1 : 0);
      methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
      return;
    }
    if (value instanceof String) {
      methodVisitor.visitLdcInsn(value);
      return;
    }
    if (value instanceof Character) {
      loadInteger(methodVisitor, (Character) value);
      methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
      return;
    }
    if (value instanceof GoloParser.ParserClassRef) {
      GoloParser.ParserClassRef ref = (GoloParser.ParserClassRef) value;
      methodVisitor.visitInvokeDynamicInsn(ref.name.replaceAll("\\.", "#"), "()Ljava/lang/Class;", CLASSREF_HANDLE);
      return;
    }
    if (value instanceof GoloParser.FunctionRef) {
      GoloParser.FunctionRef ref = (GoloParser.FunctionRef) value;
      String module = ref.module;
      if (module == null) {
        module = klass;
      }
      methodVisitor.visitLdcInsn(ref.name);
      methodVisitor.visitInvokeDynamicInsn(module.replaceAll("\\.", "#"), "()Ljava/lang/Class;", CLASSREF_HANDLE);
      methodVisitor.visitInvokeDynamicInsn(
          "gololang#Predefined#fun",
          "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
          FUNCTION_INVOCATION_HANDLE,
          (Object) 1); // this specific call can be banged
      return;
    }
    if (value instanceof Double) {
      double d = (Double) value;
      methodVisitor.visitLdcInsn(d);
      methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
      return;
    }
    if (value instanceof Float) {
      float f = (Float) value;
      methodVisitor.visitLdcInsn(f);
      methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
      return;
    }
    throw new IllegalArgumentException("Constants of type " + value.getClass() + " cannot be handled.");
  }

  @Override
  public void visitReturnStatement(ReturnStatement returnStatement) {
    returnStatement.getExpressionStatement().accept(this);
    if (returnStatement.isReturningVoid()) {
      methodVisitor.visitInsn(RETURN);
    } else {
      methodVisitor.visitInsn(ARETURN);
    }

  }

  @Override
  public void visitThrowStatement(ThrowStatement throwStatement) {
    throwStatement.getExpressionStatement().accept(this);
    methodVisitor.visitTypeInsn(CHECKCAST, "java/lang/Throwable");
    methodVisitor.visitInsn(ATHROW);
  }

  private List<String> visitInvocationArguments(AbstractInvocation invocation) {
    List<String> argumentNames = new ArrayList<>();
    for (ExpressionStatement argument : invocation.getArguments()) {
      if (invocation.usesNamedArguments()) {
        NamedArgument namedArgument = (NamedArgument) argument;
        argumentNames.add(namedArgument.getName());
        argument = namedArgument.getExpression();
      }
      argument.accept(this);
    }
    return argumentNames;
  }

  @Override
  public void visitFunctionInvocation(FunctionInvocation functionInvocation) {
    String name = functionInvocation.getName().replaceAll("\\.", "#");
    String typeDef = goloFunctionSignature(functionInvocation.getArity());
    Handle handle = FUNCTION_INVOCATION_HANDLE;
    List<Object> bootstrapArgs = new ArrayList<>();
    bootstrapArgs.add(functionInvocation.isConstant() ? 1 : 0);
    if (functionInvocation.isOnReference()) {
      ReferenceTable table = context.referenceTableStack.peek();
      methodVisitor.visitVarInsn(ALOAD, table.get(functionInvocation.getName()).getIndex());
    }
    if (functionInvocation.isOnModuleState()) {
      visitReferenceLookup(new ReferenceLookup(functionInvocation.getName()));
    }
    if (functionInvocation.isAnonymous() || functionInvocation.isOnReference() || functionInvocation.isOnModuleState()) {
      methodVisitor.visitTypeInsn(CHECKCAST, "gololang/FunctionReference");
      MethodType type = genericMethodType(functionInvocation.getArity() + 1).changeParameterType(0, FunctionReference.class);
      typeDef = type.toMethodDescriptorString();
      handle = CLOSURE_INVOCATION_HANDLE;
    }
    List<String> argumentNames = visitInvocationArguments(functionInvocation);
    bootstrapArgs.addAll(argumentNames);
    methodVisitor.visitInvokeDynamicInsn(name, typeDef, handle, bootstrapArgs.toArray());
    for (FunctionInvocation invocation : functionInvocation.getAnonymousFunctionInvocations()) {
      invocation.accept(this);
    }
  }

  @Override
  public void visitMethodInvocation(MethodInvocation methodInvocation) {
    List<Object> bootstrapArgs = new ArrayList<>();
    bootstrapArgs.add(methodInvocation.isNullSafeGuarded() ? 1 : 0);
    List<String> argumentNames = visitInvocationArguments(methodInvocation);
    bootstrapArgs.addAll(argumentNames);
    methodVisitor.visitInvokeDynamicInsn(
        methodInvocation.getName().replaceAll("\\.", "#"),
        goloFunctionSignature(methodInvocation.getArity() + 1),
        METHOD_INVOCATION_HANDLE,
        bootstrapArgs.toArray());
    for (FunctionInvocation invocation : methodInvocation.getAnonymousFunctionInvocations()) {
      invocation.accept(this);
    }
  }

  @Override
  public void visitAssignmentStatement(AssignmentStatement assignmentStatement) {
    assignmentStatement.getExpressionStatement().accept(this);
    LocalReference reference = assignmentStatement.getLocalReference();
    if (reference.isModuleState()) {
      methodVisitor.visitInvokeDynamicInsn(
          (klass + "." + reference.getName()).replaceAll("\\.", "#"),
          "(Ljava/lang/Object;)V",
          FUNCTION_INVOCATION_HANDLE,
          (Object) 0);
    } else {
      methodVisitor.visitVarInsn(ASTORE, reference.getIndex());
    }
  }

  @Override
  public void visitReferenceLookup(ReferenceLookup referenceLookup) {
    LocalReference reference = referenceLookup.resolveIn(context.referenceTableStack.peek());
    if (reference.isModuleState()) {
      methodVisitor.visitInvokeDynamicInsn(
          (klass + "." + referenceLookup.getName()).replaceAll("\\.", "#"),
          "()Ljava/lang/Object;",
          FUNCTION_INVOCATION_HANDLE,
          (Object) 0);
    } else {
      methodVisitor.visitVarInsn(ALOAD, reference.getIndex());
    }
  }

  @Override
  public void visitConditionalBranching(ConditionalBranching conditionalBranching) {
    Label branchingElseLabel = new Label();
    Label branchingExitLabel = new Label();
    conditionalBranching.getCondition().accept(this);
    asmBooleanValue();
    methodVisitor.visitJumpInsn(IFEQ, branchingElseLabel);
    conditionalBranching.getTrueBlock().accept(this);
    if (conditionalBranching.hasFalseBlock()) {
      if (!conditionalBranching.getTrueBlock().hasReturn()) {
        methodVisitor.visitJumpInsn(GOTO, branchingExitLabel);
      }
      methodVisitor.visitLabel(branchingElseLabel);
      conditionalBranching.getFalseBlock().accept(this);
      methodVisitor.visitLabel(branchingExitLabel);
    } else if (conditionalBranching.hasElseConditionalBranching()) {
      if (!conditionalBranching.getTrueBlock().hasReturn()) {
        methodVisitor.visitJumpInsn(GOTO, branchingExitLabel);
      }
      methodVisitor.visitLabel(branchingElseLabel);
      conditionalBranching.getElseConditionalBranching().accept(this);
      methodVisitor.visitLabel(branchingExitLabel);
    } else {
      methodVisitor.visitLabel(branchingElseLabel);
    }
  }

  @Override
  public void visitLoopStatement(LoopStatement loopStatement) {
    // TODO handle init and post statement and potential reference scoping issues
    Label loopStart = new Label();
    Label loopEnd = new Label();
    context.loopStartMap.put(loopStatement, loopStart);
    context.loopEndMap.put(loopStatement, loopEnd);
    if (loopStatement.hasInitStatement()) {
      loopStatement.getInitStatement().accept(this);
    }
    methodVisitor.visitLabel(loopStart);
    loopStatement.getConditionStatement().accept(this);
    asmBooleanValue();
    methodVisitor.visitJumpInsn(IFEQ, loopEnd);
    loopStatement.getBlock().accept(this);
    if (loopStatement.hasPostStatement()) {
      loopStatement.getPostStatement().accept(this);
    }
    methodVisitor.visitJumpInsn(GOTO, loopStart);
    methodVisitor.visitLabel(loopEnd);
  }

  @Override
  public void visitLoopBreakFlowStatement(LoopBreakFlowStatement loopBreakFlowStatement) {
    Label jumpTarget;
    if (LoopBreakFlowStatement.Type.BREAK.equals(loopBreakFlowStatement.getType())) {
      jumpTarget = context.loopEndMap.get(loopBreakFlowStatement.getEnclosingLoop());
    } else {
      jumpTarget = context.loopStartMap.get(loopBreakFlowStatement.getEnclosingLoop());
    }
    methodVisitor.visitLdcInsn(0);
    methodVisitor.visitJumpInsn(IFEQ, jumpTarget);
    // NOP + ATHROW invalid frames if the GOTO is followed by an else branch code...
    // methodVisitor.visitJumpInsn(GOTO, jumpTarget);
  }

  @Override
  public void visitCollectionLiteral(CollectionLiteral collectionLiteral) {
    switch (collectionLiteral.getType()) {
      case tuple:
        createTuple(collectionLiteral);
        break;
      case array:
        createArray(collectionLiteral);
        break;
      case list:
        createList(collectionLiteral);
        break;
      case vector:
        createVector(collectionLiteral);
        break;
      case set:
        createSet(collectionLiteral);
        break;
      case map:
        createMap(collectionLiteral);
        break;
      default:
        throw new UnsupportedOperationException("Can't handle collections of type " + collectionLiteral.getType() + " yet");
    }
  }

  private void createMap(CollectionLiteral collectionLiteral) {
    methodVisitor.visitTypeInsn(NEW, "java/util/LinkedHashMap");
    methodVisitor.visitInsn(DUP);
    methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/util/LinkedHashMap", "<init>", "()V", false);
    for (ExpressionStatement expression : collectionLiteral.getExpressions()) {
      methodVisitor.visitInsn(DUP);
      expression.accept(this);
      methodVisitor.visitTypeInsn(CHECKCAST, "gololang/Tuple");
      methodVisitor.visitInsn(DUP);
      loadInteger(methodVisitor, 0);
      methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "gololang/Tuple", "get", "(I)Ljava/lang/Object;", false);
      methodVisitor.visitInsn(SWAP);
      loadInteger(methodVisitor, 1);
      methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "gololang/Tuple", "get", "(I)Ljava/lang/Object;", false);
      methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/util/LinkedHashMap", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
      methodVisitor.visitInsn(POP);
    }
  }

  private void createSet(CollectionLiteral collectionLiteral) {
    methodVisitor.visitTypeInsn(NEW, "java/util/LinkedHashSet");
    methodVisitor.visitInsn(DUP);
    methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/util/LinkedHashSet", "<init>", "()V", false);
    for (ExpressionStatement expression : collectionLiteral.getExpressions()) {
      methodVisitor.visitInsn(DUP);
      expression.accept(this);
      methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/util/LinkedHashSet", "add", "(Ljava/lang/Object;)Z", false);
      methodVisitor.visitInsn(POP);
    }
  }

  private void createVector(CollectionLiteral collectionLiteral) {
    methodVisitor.visitTypeInsn(NEW, "java/util/ArrayList");
    methodVisitor.visitInsn(DUP);
    loadInteger(methodVisitor, collectionLiteral.getExpressions().size());
    methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "(I)V", false);
    for (ExpressionStatement expression : collectionLiteral.getExpressions()) {
      methodVisitor.visitInsn(DUP);
      expression.accept(this);
      methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false);
      methodVisitor.visitInsn(POP);
    }
  }

  private void createList(CollectionLiteral collectionLiteral) {
    methodVisitor.visitTypeInsn(NEW, "java/util/LinkedList");
    methodVisitor.visitInsn(DUP);
    methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/util/LinkedList", "<init>", "()V", false);
    for (ExpressionStatement expression : collectionLiteral.getExpressions()) {
      methodVisitor.visitInsn(DUP);
      expression.accept(this);
      methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/util/LinkedList", "add", "(Ljava/lang/Object;)Z", false);
      methodVisitor.visitInsn(POP);
    }
  }

  private void createArray(CollectionLiteral collectionLiteral) {
    loadInteger(methodVisitor, collectionLiteral.getExpressions().size());
    methodVisitor.visitTypeInsn(ANEWARRAY, "java/lang/Object");
    int i = 0;
    for (ExpressionStatement expression : collectionLiteral.getExpressions()) {
      methodVisitor.visitInsn(DUP);
      loadInteger(methodVisitor, i);
      expression.accept(this);
      methodVisitor.visitInsn(AASTORE);
      i = i + 1;
    }
  }

  private void createTuple(CollectionLiteral collectionLiteral) {
    methodVisitor.visitTypeInsn(NEW, "gololang/Tuple");
    methodVisitor.visitInsn(DUP);
    createArray(collectionLiteral);
    methodVisitor.visitMethodInsn(INVOKESPECIAL, "gololang/Tuple", "<init>", "([Ljava/lang/Object;)V", false);
  }

  @Override
  public void visitTryCatchFinally(TryCatchFinally tryCatchFinally) {
    Label tryStart = new Label();
    Label tryEnd = new Label();
    Label catchStart = new Label();
    Label catchEnd = new Label();

    Label rethrowStart = null;
    Label rethrowEnd = null;
    if (tryCatchFinally.isTryCatchFinally()) {
      rethrowStart = new Label();
      rethrowEnd = new Label();
    }

    methodVisitor.visitLabel(tryStart);
    tryCatchFinally.getTryBlock().accept(this);
    if (tryCatchFinally.isTryCatch() || tryCatchFinally.isTryCatchFinally()) {
      methodVisitor.visitJumpInsn(GOTO, catchEnd);
    }
    methodVisitor.visitTryCatchBlock(tryStart, tryEnd, catchStart, null);
    methodVisitor.visitLabel(tryEnd);

    if (tryCatchFinally.isTryFinally()) {
      tryCatchFinally.getFinallyBlock().accept(this);
      methodVisitor.visitJumpInsn(GOTO, catchEnd);
    }

    if (tryCatchFinally.isTryCatchFinally()) {
      methodVisitor.visitTryCatchBlock(catchStart, catchEnd, rethrowStart, null);
    }

    methodVisitor.visitLabel(catchStart);
    if (tryCatchFinally.isTryCatch() || tryCatchFinally.isTryCatchFinally()) {
      Block catchBlock = tryCatchFinally.getCatchBlock();
      int exceptionRefIndex = catchBlock.getReferenceTable().get(tryCatchFinally.getExceptionId()).getIndex();
      methodVisitor.visitVarInsn(ASTORE, exceptionRefIndex);
      tryCatchFinally.getCatchBlock().accept(this);
    } else {
      tryCatchFinally.getFinallyBlock().accept(this);
      methodVisitor.visitInsn(ATHROW);
    }
    methodVisitor.visitLabel(catchEnd);

    if (tryCatchFinally.isTryCatchFinally()) {
      tryCatchFinally.getFinallyBlock().accept(this);
      methodVisitor.visitJumpInsn(GOTO, rethrowEnd);
      methodVisitor.visitLabel(rethrowStart);
      tryCatchFinally.getFinallyBlock().accept(this);
      methodVisitor.visitInsn(ATHROW);
      methodVisitor.visitLabel(rethrowEnd);
    }
  }

  @Override
  public void visitClosureReference(ClosureReference closureReference) {
    GoloFunction target = closureReference.getTarget();
    final boolean isVarArgs = target.isVarargs();
    final int arity = (isVarArgs) ? target.getArity() - 1 : target.getArity();
    final int syntheticCount = closureReference.getTarget().getSyntheticParameterCount();
    methodVisitor.visitInvokeDynamicInsn(
        target.getName(),
        methodType(FunctionReference.class).toMethodDescriptorString(),
        CLOSUREREF_HANDLE,
        klass,
        (Integer) arity,
        (Boolean) isVarArgs);
    if (syntheticCount > 0) {
      String[] refs = closureReference.getCapturedReferenceNames().toArray(new String[syntheticCount]);
      loadInteger(methodVisitor, 0);
      loadInteger(methodVisitor, syntheticCount);
      methodVisitor.visitTypeInsn(ANEWARRAY, "java/lang/Object");
      ReferenceTable table = context.referenceTableStack.peek();
      for (int i = 0; i < syntheticCount; i++) {
        methodVisitor.visitInsn(DUP);
        loadInteger(methodVisitor, i);
        methodVisitor.visitVarInsn(ALOAD, table.get(refs[i]).getIndex());
        methodVisitor.visitInsn(AASTORE);
      }
      methodVisitor.visitMethodInsn(
          INVOKEVIRTUAL,
          "gololang/FunctionReference",
          "insertArguments",
          "(I[Ljava/lang/Object;)Lgololang/FunctionReference;", false);
      if (isVarArgs) {
        methodVisitor.visitLdcInsn(Type.getType(Object[].class));
        methodVisitor.visitMethodInsn(
            INVOKEVIRTUAL,
            "gololang/FunctionReference",
            "asVarargsCollector",
            "(Ljava/lang/Class;)Lgololang/FunctionReference;", false);
      }
    }
  }

  @Override
  public void visitBinaryOperation(BinaryOperation binaryOperation) {
    OperatorType operatorType = binaryOperation.getType();
    if (AND.equals(operatorType)) {
      andOperator(binaryOperation);
    } else if (OR.equals(operatorType)) {
      orOperator(binaryOperation);
    } else {
      genericBinaryOperator(binaryOperation, operatorType);
    }
  }

  private void genericBinaryOperator(BinaryOperation binaryOperation, OperatorType operatorType) {
    binaryOperation.getLeftExpression().accept(this);
    binaryOperation.getRightExpression().accept(this);
    if (!isMethodCall(binaryOperation)) {
      String name = operatorType.name().toLowerCase();
      methodVisitor.visitInvokeDynamicInsn(name, goloFunctionSignature(2), OPERATOR_HANDLE, (Integer) 2);
    }
  }

  private void orOperator(BinaryOperation binaryOperation) {
    Label exitLabel = new Label();
    Label trueLabel = new Label();
    binaryOperation.getLeftExpression().accept(this);
    asmBooleanValue();
    methodVisitor.visitJumpInsn(IFNE, trueLabel);
    binaryOperation.getRightExpression().accept(this);
    asmBooleanValue();
    methodVisitor.visitJumpInsn(IFNE, trueLabel);
    asmFalseObject();
    methodVisitor.visitJumpInsn(GOTO, exitLabel);
    methodVisitor.visitLabel(trueLabel);
    asmTrueObject();
    methodVisitor.visitLabel(exitLabel);
  }

  private void andOperator(BinaryOperation binaryOperation) {
    Label exitLabel = new Label();
    Label falseLabel = new Label();
    binaryOperation.getLeftExpression().accept(this);
    asmBooleanValue();
    methodVisitor.visitJumpInsn(IFEQ, falseLabel);
    binaryOperation.getRightExpression().accept(this);
    asmBooleanValue();
    methodVisitor.visitJumpInsn(IFEQ, falseLabel);
    asmTrueObject();
    methodVisitor.visitJumpInsn(GOTO, exitLabel);
    methodVisitor.visitLabel(falseLabel);
    asmFalseObject();
    methodVisitor.visitLabel(exitLabel);
  }

  private void asmFalseObject() {
    methodVisitor.visitFieldInsn(GETSTATIC, "java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;");
  }

  private void asmTrueObject() {
    methodVisitor.visitFieldInsn(GETSTATIC, "java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;");
  }

  private void asmBooleanValue() {
    methodVisitor.visitTypeInsn(CHECKCAST, "java/lang/Boolean");
    methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
  }

  @Override
  public void visitUnaryOperation(UnaryOperation unaryOperation) {
    String name = unaryOperation.getType().name().toLowerCase();
    unaryOperation.getExpressionStatement().accept(this);
    methodVisitor.visitInvokeDynamicInsn(name, goloFunctionSignature(1), OPERATOR_HANDLE, (Integer) 1);
  }
}
