package edu.pku.intellimerge.model.mapping;

import edu.pku.intellimerge.model.SemanticNode;

import java.util.Optional;

/** Nodes are not one-to-one matched */
public class ThreewayMapping {
  public Optional<SemanticNode> oursNode;
  public Optional<SemanticNode> baseNode;
  public Optional<SemanticNode> theirsNode;
  //    private MappingType mappingType;

  public ThreewayMapping() {}

  public ThreewayMapping(
      Optional<SemanticNode> oursNode,
      Optional<SemanticNode> baseNode,
      Optional<SemanticNode> theirsNode) {
    this.oursNode = oursNode;
    this.baseNode = baseNode;
    this.theirsNode = theirsNode;
  }

  @Override
  public String toString() {
    return "ThreewayMapping{"
        + "oursNode="
        + oursNode
        + ", baseNode="
        + baseNode
        + ", theirsNode="
        + theirsNode
        + '}';
  }
}