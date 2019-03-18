package edu.pku.intellimerge.core;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.model.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.utils.ParserCollectionStrategy;
import com.github.javaparser.utils.ProjectRoot;
import com.github.javaparser.utils.SourceRoot;
import edu.pku.intellimerge.model.MergeScenario;
import edu.pku.intellimerge.model.SemanticEdge;
import edu.pku.intellimerge.model.SemanticNode;
import edu.pku.intellimerge.model.constant.EdgeType;
import edu.pku.intellimerge.model.constant.NodeType;
import edu.pku.intellimerge.model.constant.Side;
import edu.pku.intellimerge.model.node.*;
import edu.pku.intellimerge.util.FilesManager;
import org.jgrapht.Graph;
import org.jgrapht.graph.builder.GraphTypeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/** Build Semantic Graph for one merge scenario, with fuzzy matching instead of symbolsolving */
public class SemanticGraphBuilder2 implements Callable<Graph<SemanticNode, SemanticEdge>> {
  private static final Logger logger = LoggerFactory.getLogger(SemanticGraphBuilder2.class);
  private Graph<SemanticNode, SemanticEdge> graph;
  // incremental id, unique in one side's graph
  private int nodeCount;
  private int edgeCount;
  private boolean hasMultiModule;
  /*
   * a series of temp containers to keep relationships between node and symbol
   * if the symbol is internal: draw the edge in graph;
   * else:
   */
  private Map<SemanticNode, List<String>> importEdges = new HashMap<>();
  private Map<SemanticNode, List<String>> extendEdges = new HashMap<>();
  private Map<SemanticNode, List<String>> implementEdges = new HashMap<>();
  private Map<SemanticNode, List<String>> declObjectEdges = new HashMap<>();
  private Map<SemanticNode, List<String>> initObjectEdges = new HashMap<>();
  private Map<SemanticNode, List<FieldAccessExpr>> readFieldEdges = new HashMap<>();
  private Map<SemanticNode, List<FieldAccessExpr>> writeFieldEdges = new HashMap<>();
  private Map<SemanticNode, List<MethodCallExpr>> methodCallExprs = new HashMap<>();

  private MergeScenario mergeScenario;
  private Side side;
  private String targetDir; // directory of the target files to be analyzed

  public SemanticGraphBuilder2(
      MergeScenario mergeScenario, Side side, String targetDir, boolean hasMultiModule) {
    this.mergeScenario = mergeScenario;
    this.side = side;
    this.targetDir = targetDir;
    this.hasMultiModule = hasMultiModule;

    this.graph = initGraph();
    this.nodeCount = 0;
    this.edgeCount = 0;
  }

  /**
   * Build and initialize an empty Graph
   *
   * @return
   */
  public static Graph<SemanticNode, SemanticEdge> initGraph() {
    return GraphTypeBuilder.<SemanticNode, SemanticEdge>directed()
        .allowingMultipleEdges(true)
        .allowingSelfLoops(true) // recursion
        .edgeClass(SemanticEdge.class)
        .weighted(true)
        .buildGraph();
  }

  /**
   * Build the graph by parsing the collected files
   *
   * @return
   */
  @Override
  public Graph<SemanticNode, SemanticEdge> call() {

    // the folder path which contains collected files to build the graph upon
    String sideDir = targetDir + File.separator + side.asString() + File.separator;
    // just for sure: reinit the graph
    this.graph = initGraph();

    // parse all java files in the file
    // regular project: only one source folder
    File root = new File(sideDir);
    //    sourceRoot.getParserConfiguration().setSymbolResolver(symbolSolver);

    List<CompilationUnit> compilationUnits = new ArrayList<>();
    List<ParseResult<CompilationUnit>> parseResults = new ArrayList<>();
    if (hasMultiModule) {
      // multi-module project: separated source folder for sub-projects/modules
      ProjectRoot projectRoot = new ParserCollectionStrategy().collect(root.toPath());

      for (SourceRoot sourceRoot : projectRoot.getSourceRoots()) {
        parseResults.addAll(sourceRoot.tryToParseParallelized());
      }
    } else {
      SourceRoot sourceRoot = new SourceRoot(root.toPath());
      parseResults = sourceRoot.tryToParseParallelized();
    }
    compilationUnits.addAll(
        parseResults
            .stream()
            .filter(ParseResult::isSuccessful)
            .map(r -> r.getResult().get())
            .collect(Collectors.toList()));

    /*
     * build the graph by analyzing every CU
     */
    logger.info("({}) CUs in {}", compilationUnits.size(), side);
    for (CompilationUnit cu : compilationUnits) {
      processCompilationUnit(cu);
    }

    // now vertices are fixed

    // build the recorded edges actually
    // TODO import can be any type, even inner type
    edgeCount = buildEdges(graph, edgeCount, importEdges, EdgeType.IMPORT, NodeType.CLASS);
    edgeCount = buildEdges(graph, edgeCount, extendEdges, EdgeType.EXTEND, NodeType.CLASS);
    edgeCount =
        buildEdges(graph, edgeCount, implementEdges, EdgeType.IMPLEMENT, NodeType.INTERFACE);
    edgeCount = buildEdges(graph, edgeCount, declObjectEdges, EdgeType.DECLARE, NodeType.CLASS);
    edgeCount = buildEdges(graph, edgeCount, initObjectEdges, EdgeType.INITIALIZE, NodeType.CLASS);

    //    edgeCount = buildEdges(graph, edgeCount, readFieldEdges, EdgeType.READ,
    // NodeType.FIELD);
    //    edgeCount = buildEdges(graph, edgeCount, writeFieldEdges, EdgeType.WRITE,
    // NodeType.FIELD);

    edgeCount = buildEdgesForMethodCall(edgeCount, methodCallExprs);

    // now edges are fixed
    // save incoming edges and outgoing edges in corresponding nodes
    for (SemanticNode node : graph.vertexSet()) {
      Set<SemanticEdge> incommingEdges = graph.incomingEdgesOf(node);
      for (SemanticEdge edge : incommingEdges) {
        if (node.incomingEdges.containsKey(edge.getEdgeType())) {
          node.incomingEdges.get(edge.getEdgeType()).add(graph.getEdgeSource(edge));
        } else {
          logger.error("Unexpected in edge:" + edge);
        }
      }
      Set<SemanticEdge> outgoingEdges = graph.outgoingEdgesOf(node);
      for (SemanticEdge edge : outgoingEdges) {
        if (node.outgoingEdges.containsKey(edge.getEdgeType())) {
          node.outgoingEdges.get(edge.getEdgeType()).add(graph.getEdgeTarget(edge));
        } else {
          logger.error("Unexpected out edge:" + edge);
        }
      }
    }

    return graph;
  }

  /**
   * Process one CompilationUnit every time
   *
   * @param cu
   */
  private void processCompilationUnit(CompilationUnit cu) {
    String fileName = cu.getStorage().map(CompilationUnit.Storage::getFileName).orElse("");
    String absolutePath =
        FilesManager.formatPathSeparator(
            cu.getStorage().map(CompilationUnit.Storage::getPath).map(Path::toString).orElse(""));
    String relativePath =
        absolutePath.replace(
            FilesManager.formatPathSeparator(
                targetDir + File.separator + side.asString() + File.separator),
            "");

    // whether this file is modified: if yes, all nodes in it need to be merged (rough way)
    boolean isInChangedFile =
        mergeScenario == null ? true : mergeScenario.isInChangedFile(side, relativePath);

    CompilationUnitNode cuNode =
        new CompilationUnitNode(
            nodeCount++,
            isInChangedFile,
            NodeType.CU,
            fileName,
            fileName,
            fileName,
            cu.getComment().map(Comment::toString).orElse(""),
            fileName,
            relativePath,
            absolutePath,
            cu.getPackageDeclaration().map(PackageDeclaration::toString).orElse(""),
            cu.getImports()
                .stream()
                .map(ImportDeclaration::toString)
                .collect(Collectors.toCollection(LinkedHashSet::new)));

    // 1. package
    String packageName = "";
    if (cu.getPackageDeclaration().isPresent()) {
      PackageDeclaration packageDeclaration = cu.getPackageDeclaration().get();
      packageName = packageDeclaration.getNameAsString();
      cuNode.setQualifiedName(packageName + "." + fileName);
      // check if the package node exists
      // if not exist, create one
      String finalPackageName = packageName;
      Optional<SemanticNode> packageDeclNodeOpt =
          graph
              .vertexSet()
              .stream()
              .filter(
                  node ->
                      node.getNodeType().equals(NodeType.PACKAGE)
                          && node.getQualifiedName().equals(finalPackageName))
              .findAny();
      if (!packageDeclNodeOpt.isPresent()) {
        PackageDeclNode packageDeclNode =
            new PackageDeclNode(
                nodeCount++,
                isInChangedFile,
                NodeType.PACKAGE,
                finalPackageName,
                packageDeclaration.getNameAsString(),
                packageDeclaration.toString().trim(),
                packageDeclaration.getComment().map(Comment::toString).orElse(""),
                finalPackageName,
                Arrays.asList(finalPackageName.split(".")));
        graph.addVertex(cuNode);
        graph.addVertex(packageDeclNode);

        packageDeclNode.appendChild(cuNode);
        graph.addEdge(
            packageDeclNode,
            cuNode,
            new SemanticEdge(edgeCount++, EdgeType.CONTAIN, packageDeclNode, cuNode));
      } else {
        graph.addVertex(cuNode);
        packageDeclNodeOpt.get().appendChild(cuNode);
        graph.addEdge(
            packageDeclNodeOpt.get(),
            cuNode,
            new SemanticEdge(edgeCount++, EdgeType.CONTAIN, packageDeclNodeOpt.get(), cuNode));
      }
    }
    // 2. import
    List<ImportDeclaration> importDeclarations = cu.getImports();
    List<String> importedClassNames = new ArrayList<>();
    for (ImportDeclaration importDeclaration : importDeclarations) {
      importedClassNames.add(
          importDeclaration.getNameAsString().trim().replace("import ", "").replace(";", ""));
    }

    // 3. type declaration: annotation, enum, class/interface
    // getTypes() returns top level types declared in this compilation unit
    for (TypeDeclaration td : cu.getTypes()) {
      //        td.getMembers()
      TypeDeclNode tdNode = processTypeDeclaration(td, packageName, nodeCount++, isInChangedFile);
      graph.addVertex(tdNode);

      if (td.isTopLevelType()) {

        cuNode.appendChild(tdNode);
        graph.addEdge(
            cuNode, tdNode, new SemanticEdge(edgeCount++, EdgeType.DEFINE, cuNode, tdNode));

        if (td.isClassOrInterfaceDeclaration()) {
          ClassOrInterfaceDeclaration cid = (ClassOrInterfaceDeclaration) td;
          // extend/implement
          if (cid.getExtendedTypes().size() > 0) {
            // single extends
            String extendedType = cid.getExtendedTypes().get(0).getNameAsString();
            List<String> temp = new ArrayList<>();
            temp.add(extendedType);
            tdNode.setExtendType(extendedType);
            extendEdges.put(tdNode, temp);
          }
          if (cid.getImplementedTypes().size() > 0) {
            List<String> implementedTypes = new ArrayList<>();
            // multiple implements
            cid.getImplementedTypes()
                .forEach(
                    implementedType -> implementedTypes.add(implementedType.getNameAsString()));
            tdNode.setImplementTypes(implementedTypes);
            implementEdges.put(tdNode, implementedTypes);
          }

          // class-imports-class(es)
          importEdges.put(cuNode, importedClassNames);
        }
        processMemebers(td, tdNode, packageName, isInChangedFile);
      }
    }
  }

  /**
   * Process the type declaration itself
   *
   * @param td
   * @param packageName
   * @param nodeCount
   * @param isInChangedFile
   * @return
   */
  private TypeDeclNode processTypeDeclaration(
      TypeDeclaration td, String packageName, int nodeCount, boolean isInChangedFile) {
    String displayName = td.getNameAsString();
    String qualifiedName = packageName + "." + displayName;
    // enum/interface/inner/local class
    NodeType nodeType = NodeType.CLASS; // default
    nodeType = td.isEnumDeclaration() ? NodeType.ENUM : nodeType;
    nodeType = td.isAnnotationDeclaration() ? NodeType.ANNOTATION : nodeType;
    if (td.isClassOrInterfaceDeclaration()) {
      ClassOrInterfaceDeclaration cid = (ClassOrInterfaceDeclaration) td;
      nodeType = cid.isInterface() ? NodeType.INTERFACE : nodeType;
      nodeType = cid.isInnerClass() ? NodeType.INNER_CLASS : nodeType;
      nodeType = cid.isLocalClassDeclaration() ? NodeType.LOCAL_CLASS : nodeType;
    }
    List<String> modifiers = new ArrayList<>();

    String access = td.getAccessSpecifier().asString();
    // why the map(Modifier::toString) cannot be resolved for td, but no problem with md and fd?
    modifiers =
        (List<String>)
            td.getModifiers()
                .stream()
                .map(modifier -> modifier.toString())
                .collect(Collectors.toList());

    String originalSignature = getTypeOriginalSignature(td);

    TypeDeclNode tdNode =
        new TypeDeclNode(
            nodeCount,
            isInChangedFile,
            nodeType,
            displayName,
            qualifiedName,
            originalSignature,
            td.getComment().map(Comment::toString).orElse(""),
            access,
            modifiers,
            nodeType.asString(),
            displayName);
    return tdNode;
  }

  /**
   * Process members (child nodes that are field, constructor or method) of type declaration
   *
   * @param td
   * @param tdNode
   * @param packageName
   * @param isInChangedFile
   */
  private void processMemebers(
      TypeDeclaration td, TypeDeclNode tdNode, String packageName, boolean isInChangedFile) {
    String qualifiedTypeName = packageName + "." + td.getNameAsString();
    List<String> modifiers;
    String access, displayName, qualifiedName, originalSignature;
    // if contains nested type declaration, iterate into it
    List<Node> orderedChildNodes = new ArrayList<>(td.getChildNodes());
    orderedChildNodes.sort(
        new Comparator<Node>() {
          @Override
          public int compare(Node o1, Node o2) {
            Integer startLine1 = o1.getRange().get().begin.line;
            Integer startLine2 = o2.getRange().get().begin.line;
            return startLine1.compareTo(startLine2);
          }
        });

    for (Node child : orderedChildNodes) {
      if (child instanceof TypeDeclaration) {
        TypeDeclaration childTD = (TypeDeclaration) child;
        if (childTD.isNestedType()) {
          // add edge from the parent td to the nested td
          TypeDeclNode childTDNode =
              processTypeDeclaration(childTD, qualifiedTypeName, nodeCount++, isInChangedFile);
          graph.addVertex(childTDNode);

          tdNode.appendChild(childTDNode);
          graph.addEdge(
              tdNode,
              childTDNode,
              new SemanticEdge(edgeCount++, EdgeType.DEFINE, tdNode, childTDNode));
          // process nested td members iteratively
          processMemebers(childTD, childTDNode, qualifiedTypeName, isInChangedFile);
        }
      } else {
        // for other members (constructor, field, method), create the node
        // add the edge from the parent td to the member
        if (child instanceof EnumConstantDeclaration) {
          EnumConstantDeclaration ecd = (EnumConstantDeclaration) child;
          displayName = ecd.getNameAsString();
          qualifiedName = qualifiedTypeName + "." + displayName;
          String body = "(" + ecd.getChildNodes().get(1).toString() + ")";
          EnumConstantDeclNode ecdNode =
              new EnumConstantDeclNode(
                  nodeCount++,
                  isInChangedFile,
                  NodeType.ENUM_CONSTANT,
                  displayName,
                  qualifiedName,
                  displayName,
                  ecd.getComment().map(Comment::toString).orElse(""),
                  body,
                  ecd.getRange());
          graph.addVertex(ecdNode);

          // add edge between field and class
          tdNode.appendChild(ecdNode);
          graph.addEdge(
              tdNode, ecdNode, new SemanticEdge(edgeCount++, EdgeType.DEFINE, tdNode, ecdNode));
        }
        // 4. field
        if (child instanceof FieldDeclaration) {
          FieldDeclaration fd = (FieldDeclaration) child;
          // there can be more than one var declared in one field declaration, add one node for
          // each
          modifiers =
              fd.getModifiers().stream().map(Modifier::toString).collect(Collectors.toList());

          access = fd.getAccessSpecifier().asString();
          for (VariableDeclarator field : fd.getVariables()) {
            displayName = field.toString();
            qualifiedName = qualifiedTypeName + "." + displayName;
            originalSignature = getFieldOriginalSignature(fd);
            String body =
                field.getInitializer().isPresent()
                    ? "=" + field.getInitializer().get().toString() + ";"
                    : ";";

            FieldDeclNode fdNode =
                new FieldDeclNode(
                    nodeCount++,
                    isInChangedFile,
                    NodeType.FIELD,
                    displayName,
                    qualifiedName,
                    originalSignature,
                    fd.getComment().map(Comment::toString).orElse(""),
                    access,
                    modifiers,
                    field.getTypeAsString(),
                    field.getNameAsString(),
                    body,
                    field.getRange());
            graph.addVertex(fdNode);

            // add edge between field and class
            tdNode.appendChild(fdNode);
            graph.addEdge(
                tdNode, fdNode, new SemanticEdge(edgeCount++, EdgeType.DEFINE, tdNode, fdNode));
            // 4.1 object creation in field declaration
            List<String> declClassNames = new ArrayList<>();
            List<String> initClassNames = new ArrayList<>();
            if (field.getType().isClassOrInterfaceType()) {
              ClassOrInterfaceType type = (ClassOrInterfaceType) field.getType();
              String classUsedInFieldName = type.getNameAsString();
              if (field.getInitializer().isPresent()) {
                initClassNames.add(classUsedInFieldName);
              } else {
                declClassNames.add(classUsedInFieldName);
              }
            }
            if (declClassNames.size() > 0) {
              declObjectEdges.put(fdNode, declClassNames);
            }
            if (initClassNames.size() > 0) {
              initObjectEdges.put(fdNode, initClassNames);
            }
          }
        }

        // 5. constructor
        if (child instanceof ConstructorDeclaration) {
          ConstructorDeclaration cd = (ConstructorDeclaration) child;
          displayName = cd.getSignature().toString();
          qualifiedName = qualifiedTypeName + "." + displayName;
          ConstructorDeclNode cdNode =
              new ConstructorDeclNode(
                  nodeCount++,
                  isInChangedFile,
                  NodeType.CONSTRUCTOR,
                  displayName,
                  qualifiedName,
                  cd.getDeclarationAsString(),
                  cd.getComment().map(Comment::toString).orElse(""),
                  displayName,
                  cd.getBody().toString(),
                  cd.getRange());
          graph.addVertex(cdNode);

          tdNode.appendChild(cdNode);
          graph.addEdge(
              tdNode, cdNode, new SemanticEdge(edgeCount++, EdgeType.DEFINE, tdNode, cdNode));

          processMethodOrConstructorBody(cd, cdNode);
        }
        // 6. method
        if (child instanceof MethodDeclaration) {
          MethodDeclaration md = (MethodDeclaration) child;
          if (md.getAnnotations().size() > 0) {
            if (md.isAnnotationPresent("Override")) {
              // search the method signature in its superclass or interface
            }
          }
          displayName = md.getSignature().toString();
          qualifiedName = qualifiedTypeName + "." + displayName;
          modifiers =
              md.getModifiers().stream().map(Modifier::toString).collect(Collectors.toList());
          List<String> parameterTypes =
              md.getParameters()
                  .stream()
                  .map(Parameter::getType)
                  .map(Type::asString)
                  .collect(Collectors.toList());
          List<String> parameterNames =
              md.getParameters()
                  .stream()
                  .map(Parameter::getNameAsString)
                  .collect(Collectors.toList());
          access = md.getAccessSpecifier().asString();
          List<String> throwsExceptions =
              md.getThrownExceptions()
                  .stream()
                  .map(ReferenceType::toString)
                  .collect(Collectors.toList());
          MethodDeclNode mdNode =
              new MethodDeclNode(
                  nodeCount++,
                  isInChangedFile,
                  NodeType.METHOD,
                  displayName,
                  qualifiedName,
                  md.getDeclarationAsString(),
                  md.getComment().map(Comment::toString).orElse(""),
                  access,
                  modifiers,
                  md.getTypeAsString(),
                  displayName.substring(0, displayName.indexOf("(")),
                  parameterTypes,
                  parameterNames,
                  throwsExceptions,
                  md.getBody().map(BlockStmt::toString).orElse(";"),
                  md.getRange());
          graph.addVertex(mdNode);

          tdNode.appendChild(mdNode);
          graph.addEdge(
              tdNode, mdNode, new SemanticEdge(edgeCount++, EdgeType.DEFINE, tdNode, mdNode));

          processMethodOrConstructorBody(md, mdNode);
        }
      }
    }
  }

  /**
   * Process interactions with other nodes inside CallableDeclaration (i.e. method or constructor)
   * body
   *
   * @param cd
   * @param node
   */
  private void processMethodOrConstructorBody(CallableDeclaration cd, TerminalNode node) {
    String displayName = "";
    String qualifiedName = "";
    // 1 new instance
    List<ObjectCreationExpr> objectCreationExprs = cd.findAll(ObjectCreationExpr.class);
    List<String> createObjectNames = new ArrayList<>();
    for (ObjectCreationExpr objectCreationExpr : objectCreationExprs) {
      String typeName = objectCreationExpr.getTypeAsString();
      createObjectNames.add(typeName);
    }
    if (createObjectNames.size() > 0) {
      initObjectEdges.put(node, createObjectNames);
    }

    // 2 field access
    // TODO support self field access
    List<FieldAccessExpr> fieldAccessExprs = cd.findAll(FieldAccessExpr.class);
    List<FieldAccessExpr> readFieldExprs = new ArrayList<>();
    List<FieldAccessExpr> writeFieldExprs = new ArrayList<>();
    for (FieldAccessExpr fieldAccessExpr : fieldAccessExprs) {
      // internal types
      // whether the field is assigned a value
      if (fieldAccessExpr.getParentNode().isPresent()) {
        Node parent = fieldAccessExpr.getParentNode().get();
        if (parent instanceof AssignExpr) {
          AssignExpr parentAssign = (AssignExpr) parent;
          if (parentAssign.getTarget().equals(fieldAccessExpr)) {
            writeFieldExprs.add(fieldAccessExpr);
          }
        }
      }
      readFieldExprs.add(fieldAccessExpr);
    }
    if (readFieldExprs.size() > 0) {
      readFieldEdges.put(node, readFieldExprs);
    }
    if (writeFieldExprs.size() > 0) {
      writeFieldEdges.put(node, writeFieldExprs);
    }
    // 3 method call
    List<MethodCallExpr> methodCallExprs = cd.findAll(MethodCallExpr.class);
    this.methodCallExprs.put(node, methodCallExprs);
  }

  /**
   * Get signature of field in original code
   *
   * @param fieldDeclaration
   * @return
   */
  private String getFieldOriginalSignature(FieldDeclaration fieldDeclaration) {
    String source = removeComment(fieldDeclaration.toString());
    //    if (fieldDeclaration.getComment().isPresent()) {
    //      source = source.replace(fieldDeclaration.getComment().get().getContent(), "");
    //    }
    return source
        .substring(0, (source.contains("=") ? source.indexOf("=") : source.indexOf(";")))
        .trim();
  }

  /** Get signature of type in original code */
  private String getTypeOriginalSignature(TypeDeclaration typeDeclaration) {
    // remove comment if there is in string representation
    String source = removeComment(typeDeclaration.toString());
    //    if (typeDeclaration.getComment().isPresent()) {
    //      source = source.replace(typeDeclaration.getComment().get().getContent(), "");
    //    }
    return source.substring(0, source.indexOf("{")).trim();
    //    return source.trim();
  }

  /**
   * Remove comment from a string
   *
   * @param source
   * @return
   */
  private String removeComment(String source) {
    return source.replaceAll("(?:/\\*(?:[^*]|(?:\\*+[^*/]))*\\*+/)|(?://.*)", "");
  }

  /**
   * Fuzzy match methods, by method name and argument numbers (to be refined)
   *
   * @param edgeCount
   * @param methodCallExprs
   * @return
   */
  private int buildEdgesForMethodCall(
      int edgeCount, Map<SemanticNode, List<MethodCallExpr>> methodCallExprs) {
    // for every method call, find its declaration by method name and paramater num
    for (Map.Entry<SemanticNode, List<MethodCallExpr>> entry : methodCallExprs.entrySet()) {
      SemanticNode caller = entry.getKey();
      List<MethodCallExpr> exprs = entry.getValue();
      for (MethodCallExpr expr : exprs) {
        boolean edgeBuilt = false;
        String methodName = expr.getNameAsString();
        int argNum = expr.getArguments().size();
        List<SemanticNode> candidates =
            graph
                .vertexSet()
                .stream()
                .filter(
                    node -> {
                      if (node.getNodeType().equals(NodeType.METHOD)) {
                        MethodDeclNode method = (MethodDeclNode) node;
                        return method.getMethodName().equals(methodName)
                            && method.getParameterNames().size() == argNum;
                      }
                      return false;
                    })
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
          // fail to find the target node and build the edge, consider it as external
          List<String> argumentNames =
              expr.getArguments().stream().map(Expression::toString).collect(Collectors.toList());
          MethodDeclNode externalMethod =
              new MethodDeclNode(
                  nodeCount++,
                  false,
                  NodeType.METHOD,
                  expr.getNameAsString(),
                  expr.getNameAsString(),
                  expr.toString(),
                  methodName,
                  argumentNames,
                  expr.getRange());
          graph.addVertex(externalMethod);
          createEdge(edgeCount++, caller, externalMethod, EdgeType.CALL, false);
        } else {
          // TODO if fuzzy matching gets multiple results, just select the first one for now
          createEdge(
              edgeCount++,
              caller,
              candidates.get(0),
              EdgeType.CALL,
              candidates.get(0).isInternal());
        }
      }
    }
    return edgeCount;
  }

  /**
   * Create an edge in the graph, if it already exists, increase the weight by one
   *
   * @param source
   * @param target
   * @param edgeId
   * @param edgeType
   * @return
   */
  private boolean createEdge(
      int edgeId, SemanticNode source, SemanticNode target, EdgeType edgeType, boolean isInternal) {
    boolean isSuccessful =
        graph.addEdge(
            source, target, new SemanticEdge(edgeId, edgeType, source, target, isInternal));
    if (!isSuccessful) {
      SemanticEdge edge = graph.getEdge(source, target);
      if (edge != null) {
        edge.setWeight(edge.getWeight() + 1);
        isSuccessful = true;
      }
    }
    return isSuccessful;
  }
  /**
   * Add edges according to recorded temp mapping
   *
   * @param edgeCount edge id
   * @param edges recorded temp mapping from source node to qualified name of target node
   * @param edgeType edge type
   * @param targetNodeType target node type
   * @return
   */
  private int buildEdges(
      Graph<SemanticNode, SemanticEdge> semanticGraph,
      int edgeCount,
      Map<SemanticNode, List<String>> edges,
      EdgeType edgeType,
      NodeType targetNodeType) {
    if (edges.isEmpty()) {
      return edgeCount;
    }

    Set<SemanticNode> vertexSet = semanticGraph.vertexSet();
    for (Map.Entry<SemanticNode, List<String>> entry : edges.entrySet()) {
      SemanticNode sourceNode = entry.getKey();
      List<String> targetNodeNames = entry.getValue();
      for (String targeNodeName : targetNodeNames) {
        SemanticNode targetNode = null;
        if (targetNodeType.equals(NodeType.FIELD)) {
          targetNode = getTargetNodeForField(vertexSet, targeNodeName, targetNodeType);
        } else if (targetNodeType.equals(NodeType.CLASS)) {
          targetNode = getTargetNodeForType(vertexSet, targeNodeName, targetNodeType);
        } else {
          targetNode = getTargetNode(vertexSet, targeNodeName, targetNodeType);
        }
        if (targetNode != null) {
          // if the edge was added to the graph, returns true; if the edges already exists, returns
          // false
          boolean isSuccessful =
              semanticGraph.addEdge(
                  sourceNode,
                  targetNode,
                  new SemanticEdge(edgeCount++, edgeType, sourceNode, targetNode));
          if (!isSuccessful) {
            SemanticEdge edge = semanticGraph.getEdge(sourceNode, targetNode);
            edge.setWeight(edge.getWeight() + 1);
          }
        }
      }
    }
    return edgeCount;
  }

  /**
   * Get the target node from vertex set according to qualified name
   *
   * @param vertexSet
   * @param targetQualifiedName
   * @param targetNodeType
   * @return
   */
  public SemanticNode getTargetNode(
      Set<SemanticNode> vertexSet, String targetQualifiedName, NodeType targetNodeType) {
    Optional<SemanticNode> targetNodeOpt =
        vertexSet
            .stream()
            .filter(
                node ->
                    node.getNodeType().equals(targetNodeType)
                        && node.getQualifiedName().equals(targetQualifiedName))
            .findAny();
    return targetNodeOpt.orElse(null);
  }

  /**
   * Get the target node for type decl or init by fuzzy matching
   *
   * @param vertexSet
   * @param displayName
   * @param targetNodeType
   * @return
   */
  public SemanticNode getTargetNodeForType(
      Set<SemanticNode> vertexSet, String displayName, NodeType targetNodeType) {
    Optional<SemanticNode> targetNodeOpt =
        vertexSet
            .stream()
            .filter(
                node ->
                    node.getNodeType().equals(targetNodeType)
                        && node.getQualifiedName().equals(displayName))
            .findAny();
    return targetNodeOpt.orElse(null);
  }

  /**
   * Get the target node for field access by fuzzy matching
   *
   * @param vertexSet
   * @param fieldAccessString
   * @param targetNodeType
   * @return
   */
  public SemanticNode getTargetNodeForField(
      Set<SemanticNode> vertexSet, String fieldAccessString, NodeType targetNodeType) {
    // for field, match by field name
    if (fieldAccessString.contains(".")) {
      fieldAccessString =
          fieldAccessString.substring(
              fieldAccessString.lastIndexOf("."), fieldAccessString.length());
    }
    String displayName = fieldAccessString;
    // for method, match by method name and paramater num
    Optional<SemanticNode> targetNodeOpt = Optional.empty();
    if (targetNodeType.equals(NodeType.FIELD)) {

      targetNodeOpt =
          vertexSet
              .stream()
              .filter(
                  node ->
                      node.getNodeType().equals(targetNodeType)
                          && node.getDisplayName().equals(displayName))
              .findAny();
    }

    return targetNodeOpt.orElse(null);
  }

  /**
   * Setup and config the JavaSymbolSolver
   *
   * @param packagePath
   * @param libPath
   * @return
   */
  private JavaSymbolSolver setUpSymbolSolver(String packagePath, String libPath) {
    // set up the JavaSymbolSolver
    //    TypeSolver jarTypeSolver = JarTypeSolver.getJarTypeSolver(libPath);
    TypeSolver reflectionTypeSolver = new ReflectionTypeSolver();
    TypeSolver javaParserTypeSolver = new JavaParserTypeSolver(new File(packagePath));
    reflectionTypeSolver.setParent(reflectionTypeSolver);
    CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();
    combinedTypeSolver.add(reflectionTypeSolver);
    combinedTypeSolver.add(javaParserTypeSolver);
    JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedTypeSolver);
    return symbolSolver;
  }
}
