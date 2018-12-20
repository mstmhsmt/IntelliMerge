package edu.pku.intellimerge.util;

import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

public class GitServiceImpl implements GitService {
  static final Logger logger = LoggerFactory.getLogger(GitService.class);

  public Repository cloneIfNotExists(String projectPath, String cloneUrl) throws Exception {
    File folder = new File(projectPath);
    Repository repository;
    if (folder.exists()) {
      RepositoryBuilder builder = new RepositoryBuilder();
      repository =
          builder.setGitDir(new File(folder, ".git")).readEnvironment().findGitDir().build();

      logger.info(
          "Project {} is already cloned, current branch is {}", cloneUrl, repository.getBranch());
    } else {
      logger.info("Cloning {} ...", cloneUrl);
      Git git =
          Git.cloneRepository()
              .setDirectory(folder)
              .setURI(cloneUrl)
              .setCloneAllBranches(true)
              .call();
      repository = git.getRepository();
      logger.info("Done cloning {}, current branch is {}", cloneUrl, repository.getBranch());
    }
    return repository;
  }

  public Repository cloneIfNotExistsWithBranch(String projectPath, String cloneUrl, String branch)
      throws Exception {

    Repository repository = cloneIfNotExists(projectPath, cloneUrl);

    if (branch != null && !repository.getBranch().equals(branch)) {
      Git git = new Git(repository);

      String localBranch = "refs/heads/" + branch;
      List<Ref> refs = git.branchList().call();
      boolean branchExists = false;
      for (Ref ref : refs) {
        if (ref.getName().equals(localBranch)) {
          branchExists = true;
        }
      }

      if (branchExists) {
        git.checkout().setName(branch).call();
      } else {
        git.checkout()
            .setCreateBranch(true)
            .setName(branch)
            .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
            .setStartPoint("origin/" + branch)
            .call();
      }

      logger.info("Project {} switched to {}", cloneUrl, repository.getBranch());
    }
    return repository;
  }

  public void checkout(Repository repository, String commitID) throws Exception {
    logger.info(
        "Checking out {} {} ...", repository.getDirectory().getParent().toString(), commitID);
    try (Git git = new Git(repository)) {
      CheckoutCommand checkout = git.checkout().setName(commitID);
      checkout.call();
    }
    //		File workingDir = repository.getDirectory().getParentFile();
    //		ExternalProcess.execute(workingDir, "git", "checkout", commitID);
  }

  public List<DiffEntry> listDiffJavaFiles(
      Repository repository, String oldCommit, String newCommit)
      throws GitAPIException, IOException {
    try (Git git = new Git(repository)) {

      final List<DiffEntry> diffs =
          git.diff()
              .setOldTree(prepareTreeParser(repository, oldCommit))
              .setNewTree(prepareTreeParser(repository, newCommit))
              .call();

      List<DiffEntry> javaDiffs =
          diffs
              .stream()
              .filter(diffEntry -> isJavaFile(diffEntry.getNewPath()))
              .collect(Collectors.toList());
      return javaDiffs;
    }
  }

  private boolean isJavaFile(String path) {
    return path.endsWith(".java");
  }

  private AbstractTreeIterator prepareTreeParser(Repository repository, String objectId)
      throws IOException {
    // from the commit we can build the tree which allows us to construct the TreeParser
    //noinspection Duplicates
    try (RevWalk walk = new RevWalk(repository)) {
      RevCommit commit = walk.parseCommit(repository.resolve(objectId));
      RevTree tree = walk.parseTree(commit.getTree().getId());

      CanonicalTreeParser treeParser = new CanonicalTreeParser();
      try (ObjectReader reader = repository.newObjectReader()) {
        treeParser.reset(reader, tree.getId());
      }

      walk.dispose();

      return treeParser;
    }
  }
}