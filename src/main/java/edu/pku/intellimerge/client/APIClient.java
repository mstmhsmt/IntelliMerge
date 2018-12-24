package edu.pku.intellimerge.client;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import edu.pku.intellimerge.core.SemanticGraphBuilder;
import edu.pku.intellimerge.core.SemanticGraphExporter;
import edu.pku.intellimerge.model.SemanticEdge;
import edu.pku.intellimerge.model.SemanticNode;
import edu.pku.intellimerge.model.Side;
import edu.pku.intellimerge.model.SourceFile;
import edu.pku.intellimerge.util.FilesManager;
import edu.pku.intellimerge.util.GitService;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.PropertyConfigurator;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Repository;
import org.jgrapht.Graph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class APIClient {

  private static final Logger logger = LoggerFactory.getLogger(APIClient.class);

  private static final String REPO_NAME = "IntelliMerge";
  private static final String REPO_PATH = "D:\\github\\repos\\" + REPO_NAME;
  private static final String GIT_URL = "https://github.com/javaparser/javaparser.git";
  private static final String SRC_PATH = "/src/main/java/";
  //  private static final String PROJECT_PATH = "src/main/java/edu/pku/intellimerge/samples";
  private static final String DIFF_PATH = "D:\\github\\diffs\\" + REPO_NAME;

  public static void main(String[] args) {
    PropertyConfigurator.configure("log4j.properties");
    //      BasicConfigurator.configure();

    String mergeCommitID = "3ceb2c9453198631adf0f49afc10ece85ccfc295";
    String oursCommitID = "3ab30428c5c85039cafdf380627436a80386b353";
    String theirsCommitID = "3ae7bb49d9331107b941a72c97b84042eebf9c7e";
    String baseCommitID = "003eba5af74699132eb15343c9cb39cab51eb85c";

    try {
      // 1. Get changed java files between parent commit and merge base commit
      Repository repository = GitService.cloneIfNotExists(REPO_PATH, GIT_URL);

      List<DiffEntry> diffEntryList =
          GitService.listDiffFilesJava(repository, baseCommitID, oursCommitID);
      GitService.checkout(repository, oursCommitID);

      ArrayList<SourceFile> temp = new ArrayList<>();
//      ArrayList<SourceFile> javaSourceFiles =
//          FilesManager.scanJavaSourceFiles(REPO_PATH + SRC_PATH, temp, REPO_PATH);

      String diffPath =
          DIFF_PATH + "/" + oursCommitID + "/" + Side.OURS.toString().toLowerCase() + "/";

//      getFilesToParse(javaSourceFiles, diffEntryList, oursCommitID, Side.OURS, diffPath);

      Graph<SemanticNode, SemanticEdge> semanticGraph =
          SemanticGraphBuilder.buildForRepo(diffPath, REPO_PATH + SRC_PATH);
      if (semanticGraph == null) {
        System.out.println("SemanticGraph is null!");
        return;
      }
      //        for (SemanticNode node : semanticGraph.vertexSet()) {
      //            System.out.println(node);
      //        }
      //        System.out.println("------------------------------");
//      for (SemanticEdge edge : semanticGraph.edgeSet()) {
//        SemanticNode source = semanticGraph.getEdgeSource(edge);
//        SemanticNode target = semanticGraph.getEdgeTarget(edge);
//        System.out.println(
//            source.getDisplayName() + " " + edge.getEdgeType() + " " + target.getDisplayName());
//      }
      //        System.out.println("------------------------------");
      System.out.println(SemanticGraphExporter.exportAsDot(semanticGraph));

      // 2.1 Build ours/theirs graphs among changed files & their imported files (one hop)

      // 2.2 Build base/merge graphs among ours/theirs files

      // 3. Merge the 3-way graphs

      // 4. Print the merged graph into code

    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /**
   * Copy diff java files and imported java files to the diff path
   *
   * @param diffEntryList
   * @param commitID
   * @param side
   * @throws Exception
   */
  private static void getFilesToParse(
      List<SourceFile> sourceFiles,
      List<DiffEntry> diffEntryList,
      String commitID,
      Side side,
      String diffPath)
      throws Exception {

    for (DiffEntry diffEntry : diffEntryList) {
      if (diffEntry.getChangeType().equals(DiffEntry.ChangeType.MODIFY)) {
        // src/main/java/edu/pku/intellimerge/core/SemanticGraphBuilder.java
        String relativePath = diffEntry.getNewPath();
        logger.info(
            "{} : {} -> {}",
            diffEntry.getChangeType(),
            diffEntry.getOldPath(),
            diffEntry.getNewPath());
        File srcFile = new File(REPO_PATH + "/" + relativePath);
        // copy the diff files
        if (srcFile.exists()) {
          File dstFile = new File(diffPath + relativePath);

          FileUtils.copyFile(srcFile, dstFile);
          // copy the imported files
          CompilationUnit cu = JavaParser.parse(dstFile);
          for (ImportDeclaration importDeclaration : cu.getImports()) {
            String qualifiedName =
                importDeclaration.getNameAsString().trim().replace("import ", "").replace(";", "");
            for (SourceFile sourceFile : sourceFiles) {
              if (sourceFile.getQualifiedName().equals(qualifiedName) && !sourceFile.isCopied) {
                srcFile = new File(sourceFile.getAbsolutePath());
                dstFile = new File(diffPath + sourceFile.getRelativePath());
                FileUtils.copyFile(srcFile, dstFile);
              }
            }
          }
        } else {
          logger.info("{} not exists", relativePath);
        }
      }
    }
  }
}
