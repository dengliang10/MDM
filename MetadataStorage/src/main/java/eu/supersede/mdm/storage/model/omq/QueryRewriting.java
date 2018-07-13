package eu.supersede.mdm.storage.model.omq;

import com.google.common.collect.*;
import eu.supersede.mdm.storage.model.Namespaces;
import eu.supersede.mdm.storage.model.metamodel.GlobalGraph;
import eu.supersede.mdm.storage.model.metamodel.SourceGraph;
import eu.supersede.mdm.storage.model.omq.relational_operators.EquiJoin;
import eu.supersede.mdm.storage.model.omq.relational_operators.Projection;
import eu.supersede.mdm.storage.model.omq.relational_operators.RelationalOperator;
import eu.supersede.mdm.storage.model.omq.relational_operators.Wrapper;
import eu.supersede.mdm.storage.util.RDFUtil;
import eu.supersede.mdm.storage.util.Tuple2;
import eu.supersede.mdm.storage.util.Utils;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.impl.PropertyImpl;
import org.apache.jena.rdf.model.impl.ResourceImpl;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.op.OpBGP;
import org.apache.jena.sparql.algebra.op.OpJoin;
import org.apache.jena.sparql.algebra.op.OpProject;
import org.apache.jena.sparql.algebra.op.OpTable;
import org.apache.jena.sparql.core.BasicPattern;
import org.jgrapht.Graphs;
import org.jgrapht.experimental.dag.DirectedAcyclicGraph;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class QueryRewriting {

    //Used for adding triples in-memory
    private void addTriple(Model model, String s, String p, String o) {
        model.add(new ResourceImpl(s), new PropertyImpl(p), new ResourceImpl(o));
    }
    private ResultSet runAQuery(String sparqlQuery, Dataset ds) {
        try (QueryExecution qExec = QueryExecutionFactory.create(QueryFactory.create(sparqlQuery), ds)) {
            return ResultSetFactory.copyResults(qExec.execSelect());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
    private ResultSet runAQuery(String sparqlQuery, OntModel o) {
        try (QueryExecution qExec = QueryExecutionFactory.create(QueryFactory.create(sparqlQuery), o)) {
            return ResultSetFactory.copyResults(qExec.execSelect());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private Dataset T;

    private Set<String> PI;

    // We keep two representations of Q_G.\varphi to simplify its manipulation
    //  1) As a pattern to easily access its triples as a list
    //  2) As an ontology so it can be queried via SPARQL
    private BasicPattern PHI_p; //PHI_pattern
    private OntModel PHI_o; // PHI_ontology

    public QueryRewriting(String SPARQL) {
        T = Utils.getTDBDataset(); T.begin(ReadWrite.READ);

        // Compile the SPARQL using ARQ and generate its <pi,phi> representation
        Query q = QueryFactory.create(SPARQL);
        Op ARQ = Algebra.compile(q);

        PI = Sets.newHashSet();
        ((OpTable)((OpJoin)((OpProject)ARQ).getSubOp()).getLeft()).getTable().rows().forEachRemaining(r -> {
            r.vars().forEachRemaining(v -> PI.add(r.get(v).getURI()));
        });

        this.PHI_p = ((OpBGP)((OpJoin)((OpProject)ARQ).getSubOp()).getRight()).getPattern();
        PHI_o = ModelFactory.createOntologyModel();
        PHI_p.getList().forEach(t ->
            this.addTriple(PHI_o, t.getSubject().getURI(), t.getPredicate().getURI(), t.getObject().getURI())
        );
    }

    public Set<Walk> rewriteAggregations() {


        Set<Walk> out = this.rewrite();
        return out;
    }

    public Set<Walk> rewrite() {

        // Query expansion
        // 1 Identify query-related concepts
        //List<String> concepts = Lists.newArrayList();
        // First, create a graph of the pattern in order to obtain its topological sort
        DirectedAcyclicGraph<String,String> conceptsGraph = new DirectedAcyclicGraph<String, String>(String.class);
        PHI_p.getList().forEach(t -> {
            // Add only concepts so its easier to populate later the list of concepts
            if (!t.getPredicate().getURI().equals(GlobalGraph.HAS_FEATURE.val())) {
                conceptsGraph.addVertex(t.getSubject().getURI());
                conceptsGraph.addVertex(t.getObject().getURI());
                conceptsGraph.addEdge(t.getSubject().getURI(), t.getObject().getURI(), t.getPredicate().getURI());
                /*try {
                    conceptsGraph.addVertex(t.getSubject().getURI());
                    conceptsGraph.addVertex(t.getObject().getURI());
                    conceptsGraph.addDagEdge(t.getSubject().getURI(), t.getObject().getURI(), t.getPredicate().getURI());
                } catch (DirectedAcyclicGraph.CycleFoundException e) {
                    e.printStackTrace();
                }*/
            }
        });
        System.out.println(conceptsGraph);
        // This is required when only one concept is queried, where all edges are hasFeature
        /*if (concepts.isEmpty()) {
            concepts.add(PHI_p.getList().get(0).getSubject().getURI());
        }*/

        // Now, iterate using a topological sort adding the concepts to the list of concepts
        //conceptsGraph.iterator().forEachRemaining(vertex -> concepts.add(vertex));

        // 2 Expand Q_G with IDs
        conceptsGraph.iterator().forEachRemaining(c -> {
            ResultSet IDs = this.runAQuery("SELECT ?t " +
                    "WHERE { GRAPH ?g {" +
                    "<"+c+"> <"+ GlobalGraph.HAS_FEATURE.val()+"> ?t . " +
                    "?t <"+ Namespaces.rdfs.val()+"subClassOf> <"+ Namespaces.sc.val()+"identifier> " +
                    "} }", PHI_o);
            IDs.forEachRemaining(id -> {
                if (!PHI_p.getList().contains(Triple.create(NodeFactory.createURI(c),
                        NodeFactory.createURI(GlobalGraph.HAS_FEATURE.val()),id.get("t").asNode()))) {
                    PHI_p.add(Triple.create(NodeFactory.createURI(c),
                            NodeFactory.createURI(GlobalGraph.HAS_FEATURE.val()), id.get("t").asNode()));
                }
            });
        });

        // Intra-concept generation
        List<Tuple2<String,Set<Walk>>> partialWalks = Lists.newArrayList();
        // 3 Identify queried features
        conceptsGraph.iterator().forEachRemaining(c -> {
            Map<Wrapper,Set<Walk>> PartialWalksPerWrapper = Maps.newHashMap();
            ResultSet resultSetFeatures = this.runAQuery("SELECT ?f " +
                    "WHERE {<"+c+"> <"+ GlobalGraph.HAS_FEATURE.val()+"> ?f }",PHI_o);
        // 4 Unfold LAV mappings
            //Convert the resultset to set
            Set<String> features = Sets.newHashSet();
            resultSetFeatures.forEachRemaining(f -> features.add(f.get("f").asResource().getURI()));

            features.forEach(f -> {
                ResultSet wrappers = this.runAQuery("SELECT ?g " +
                        "WHERE { GRAPH ?g { <"+c+"> <"+ GlobalGraph.HAS_FEATURE.val()+"> <"+f+"> } }",T);
        // 5 Find attributes in S
                wrappers.forEachRemaining(wRes -> {
                    String w = wRes.get("g").asResource().getURI();
                    // Distinguish the ontology named graph
                    if (!w.equals(Namespaces.T.val()) && w.contains("Wrapper")/*last min bugfix*/) {
                        ResultSet rsAttr = this.runAQuery("SELECT ?a " +
                                "WHERE { GRAPH ?g { ?a <"+Namespaces.owl.val()+"sameAs> <"+f+"> . " +
                                "<"+w+"> <"+ SourceGraph.HAS_ATTRIBUTE.val()+"> ?a } }", T);
                        String attribute =null;
                        try {
                            attribute = rsAttr.nextSolution().get("a").asResource().getURI();
                        }catch(Exception exc) {
                            exc.printStackTrace();
                        }

                        if (!PartialWalksPerWrapper.containsKey(new Wrapper(w))) {
                            PartialWalksPerWrapper.put(new Wrapper(w), Sets.newHashSet());
                        }
                        Walk walk = new Walk();
                        walk.getOperators().add(new Projection(attribute));
                        walk.getOperators().add(new Wrapper(w));

                        Set<Walk> currentSet = PartialWalksPerWrapper.get(new Wrapper(w));
                        currentSet.add(walk);
                        PartialWalksPerWrapper.put(new Wrapper(w), currentSet);

                    }
                });
            });
        // 6 Prune output
            PartialWalksPerWrapper.forEach((wrapper,walk) -> {
                Walk mergedWalk = new Walk();
                mergedWalk.getOperators().add(0, new Projection());
                mergedWalk.getOperators().add(1, wrapper);
                walk.forEach(w -> {
                    w.getOperators().forEach(op -> {
                        if (op instanceof Projection) {
                            ((Projection)op).getProjectedAttributes().forEach(att -> {
                                ((Projection)mergedWalk.getOperators().get(0)).getProjectedAttributes().add(att);
                            });
                        }
                    });
                });

                Set<String> featuresInWalk = Sets.newHashSet();
                walk.forEach(w -> {
                    w.getOperators().forEach(op -> {
                        if (op instanceof Projection) {
                            ((Projection)op).getProjectedAttributes().forEach(a -> {
                                this.runAQuery("SELECT ?f " +
                                        "WHERE { GRAPH ?g " +
                                        "{<"+a+"> <"+Namespaces.owl.val()+"sameAs> ?f } }",T).forEachRemaining(featureInWalk -> {
                                    featuresInWalk.add(featureInWalk.get("f").asResource().getURI());
                                });
                            });
                        }
                    });
                });
                if (features.equals(featuresInWalk)) {
                //if (featuresInWalk.equals(features)) {
                    boolean found = false;
                    for (Tuple2<String,Set<Walk>> pw : partialWalks) {
                        if (pw._1.equals(c)) {
                            found = true;
                            pw._2().add(mergedWalk);
                        }
                    }
                    if (!found) partialWalks.add(new Tuple2<>(c,Sets.newHashSet(mergedWalk)));
                }
            });
        });

        // Technical-impromptu, convert the list of partialWalks to a DAG-based structure
        DirectedAcyclicGraph<Tuple2<String,Set<Walk>>,String> partialWalksGraph = new DirectedAcyclicGraph<>(String.class);
        partialWalks.forEach(v -> partialWalksGraph.addVertex(v));
        conceptsGraph.vertexSet().iterator().forEachRemaining(sourceConcept -> {
            Tuple2<String,Set<Walk>> sourceVertex = partialWalksGraph.vertexSet().stream().
                    filter(pwVertex -> pwVertex._1.equals(sourceConcept)).collect(Collectors.toList()).get(0);
            Graphs.neighborListOf(conceptsGraph,sourceConcept).forEach(targetConcept -> {
                Tuple2<String,Set<Walk>> targetVertex = partialWalksGraph.vertexSet().stream().
                        filter(pwVertex -> pwVertex._1.equals(targetConcept)).collect(Collectors.toList()).get(0);
                if (conceptsGraph.containsEdge(sourceConcept,targetConcept)) {
                    partialWalksGraph.addEdge(sourceVertex, targetVertex, UUID.randomUUID().toString());
                    /*try {
                        partialWalksGraph.addDagEdge(sourceVertex, targetVertex, UUID.randomUUID().toString());
                    } catch (DirectedAcyclicGraph.CycleFoundException e) {
                        e.printStackTrace();
                    }*/
                }
            });
        });

        System.out.println("PartialWalksGraphs output of phase #2");
        System.out.println("Vertex:");
        partialWalksGraph.vertexSet().stream().forEach(System.out::println);
        System.out.println("Edges:");
        partialWalksGraph.edgeSet().stream().forEach(e -> System.out.println(partialWalksGraph.getEdgeSource(e) + " --> " + partialWalksGraph.getEdgeTarget(e)));

        Tuple2<String,Set<Walk>> res = Iterables.getFirst(partialWalksGraph,null);

        // Inter-concept generation
        List<Tuple2<String,Set<Walk>>> partialWalksGraphTopologicalSort = Lists.newArrayList(partialWalksGraph.iterator());

        partialWalksGraphTopologicalSort.forEach(sourceVertex -> {
        //partialWalksGraph.iterator().forEachRemaining(sourceVertex -> {
            Graphs.neighborListOf(partialWalksGraph,sourceVertex).forEach(targetVertex -> {
                if (!partialWalksGraph.containsEdge(sourceVertex,targetVertex)) return;

                Set<Walk> joined = Sets.newHashSet();
                for (List<Walk> CP : Sets.cartesianProduct(sourceVertex._2,targetVertex._2)) {
                    Walk CP_left = CP.get(0);
                    Walk CP_right = CP.get(1);

                    Set<Wrapper> wrappersLeft = Sets.newHashSet(
                            CP_left.getOperators().stream().filter(op -> op instanceof Wrapper).map(w -> (Wrapper)w).collect(Collectors.toList())
                    );
                    Set<Wrapper> wrappersRight = Sets.newHashSet(
                            CP_right.getOperators().stream().filter(op -> op instanceof Wrapper).map(w -> (Wrapper)w).collect(Collectors.toList())
                    );

                    Walk mergedWalk = new Walk(CP_left);

                    // 8 Merge walks
                    if (!Sets.intersection(wrappersLeft,wrappersRight).isEmpty()) {
                        for (int j = 1; j < CP_right.getOperators().size(); ++j) {
                            RelationalOperator op_cpright = CP_right.getOperators().get(j);
                            if (op_cpright instanceof Wrapper) {
                                for (int k = 1; k < mergedWalk.getOperators().size(); ++k) {
                                    RelationalOperator op_mw = mergedWalk.getOperators().get(k);
                                    if (op_mw instanceof Wrapper && op_cpright.equals(op_mw) ) {
                                        ((Projection)mergedWalk.getOperators().get(k-1)).getProjectedAttributes().addAll(
                                                ((Projection)CP_right.getOperators().get(j-1)).getProjectedAttributes()
                                        );
                                    }
                                }
                            }
                        }
                    } else {
                        CP_right.getOperators().forEach(op -> mergedWalk.getOperators().add(op));
                    }

                    // 9 Discover join wrappers
                    if (Sets.intersection(wrappersLeft,wrappersRight).isEmpty()) {
                        Set<Wrapper> wrappersFromLtoR = Sets.newHashSet();
                        this.runAQuery("SELECT ?g " +
                            "WHERE { GRAPH ?g { <"+sourceVertex._1+"> ?x <"+targetVertex._1+">}}", T).
                                forEachRemaining(w -> {
                                    if (w.get("g").asResource().getURI().contains("Wrapper")) {/*last min bugfix*/
                                        //if (!w.get("g").asResource().getURI().equals(Namespaces.T.val())) {
                                        wrappersFromLtoR.add(new Wrapper(w.get("g").asResource().getURI()));
                                    }
                                });
                        Set<Wrapper> wrappersFromRtoL = Sets.newHashSet();
                        this.runAQuery("SELECT ?g " +
                            "WHERE { GRAPH ?g { <"+targetVertex._1+"> ?x <"+sourceVertex._1+">}}", T).
                                forEachRemaining(w -> {
                                    if (w.get("g").asResource().getURI().contains("Wrapper")) {/*last min bugfix*/
                                        //if (!w.get("g").asResource().getURI().equals(Namespaces.T.val()))
                                        wrappersFromRtoL.add(new Wrapper(w.get("g").asResource().getURI()));
                                    }
                                });
                        // 10 Discover join attribute
                        if (!wrappersFromLtoR.isEmpty()) {
                            String f_ID = this.runAQuery("SELECT ?t WHERE { " +
                                "GRAPH ?g { <"+targetVertex._1+"> <"+ GlobalGraph.HAS_FEATURE.val()+"> ?t . " +
                                "?t <"+Namespaces.rdfs.val()+"subClassOf> <"+Namespaces.sc.val()+"identifier> } }",T)
                                    .nextSolution().get("t").asResource().getURI();

                            // find wrapper with ID
                            Wrapper wrapperWithIDright = (Wrapper)CP_right.getOperators().get(1);

                            String att_right = this.runAQuery("SELECT ?a WHERE { GRAPH ?g {" +
                                "?a <"+Namespaces.owl.val()+"sameAs> <"+f_ID+"> . " +
                                "<"+wrapperWithIDright.getWrapper()+"> <"+ SourceGraph.HAS_ATTRIBUTE.val()+"> ?a } }",T)
                                    .nextSolution().get("a").asResource().getURI();

                            wrappersFromLtoR.forEach(w -> {
                                String att_left = this.runAQuery("SELECT ?a WHERE { GRAPH ?g {" +
                                    "?a <"+Namespaces.owl.val()+"sameAs> <"+f_ID+"> . " +
                                    "<"+w.getWrapper()+"> <"+ SourceGraph.HAS_ATTRIBUTE.val()+"> ?a } }",T)
                                        .nextSolution().get("a").asResource().getURI();

                                EquiJoin join = new EquiJoin(att_left,att_right);

                                boolean found = false;
                                for (int j = 0; j < mergedWalk.getOperators().size() && !found; ++j) {
                                    for (int k = j+1; k < mergedWalk.getOperators().size() && !found; ++k) {
                                        if (mergedWalk.getOperators().get(j) instanceof Wrapper &&
                                                mergedWalk.getOperators().get(j).equals(w) &&
                                                mergedWalk.getOperators().get(k) instanceof Wrapper &&
                                                mergedWalk.getOperators().get(k).equals(wrapperWithIDright)) {
                                            found = true;
                                            mergedWalk.getOperators().add(k-1,join);
                                        }
                                        else if (mergedWalk.getOperators().get(j) instanceof Wrapper &&
                                                mergedWalk.getOperators().get(j).equals(wrapperWithIDright) &&
                                                mergedWalk.getOperators().get(k) instanceof Wrapper &&
                                                mergedWalk.getOperators().get(k).equals(w)) {
                                            found = true;
                                            mergedWalk.getOperators().add(k-1,join);
                                        }
                                    }
                                }
                            });
                        }
                        else if (!wrappersFromRtoL.isEmpty()) {
                            String f_ID = this.runAQuery("SELECT ?t WHERE { " +
                                "GRAPH ?g { <"+sourceVertex._1+"> <"+ GlobalGraph.HAS_FEATURE.val()+"> ?t . " +
                                "?t <"+Namespaces.rdfs.val()+"subClassOf> <"+Namespaces.sc.val()+"identifier> } }",T)
                                    .nextSolution().get("t").asResource().getURI();

                            // find wrapper with ID
                            Wrapper wrapperWithIDleft = (Wrapper)CP_left.getOperators().get(1);

                            String att_left = this.runAQuery("SELECT ?a WHERE { GRAPH ?g {" +
                                "?a <"+Namespaces.owl.val()+"sameAs> <"+f_ID+"> . " +
                                "<"+wrapperWithIDleft.getWrapper()+"> <"+ SourceGraph.HAS_ATTRIBUTE.val()+"> ?a } }",T)
                                    .nextSolution().get("a").asResource().getURI();

                            wrappersFromRtoL.forEach(w -> {
                                String att_right = this.runAQuery("SELECT ?a WHERE { GRAPH ?g {" +
                                    "?a <"+Namespaces.owl.val()+"sameAs> <"+f_ID+"> . " +
                                    "<"+w.getWrapper()+"> <"+ SourceGraph.HAS_ATTRIBUTE.val()+"> ?a } }",T)
                                        .nextSolution().get("a").asResource().getURI();

                                EquiJoin join = new EquiJoin(att_left,att_right);

                                boolean found = false;
                                for (int j = 0; j < mergedWalk.getOperators().size() && !found; ++j) {
                                    for (int k = j+1; k < mergedWalk.getOperators().size() && !found; ++k) {
                                        if (mergedWalk.getOperators().get(j) instanceof Wrapper &&
                                                mergedWalk.getOperators().get(j).equals(w) &&
                                                mergedWalk.getOperators().get(k) instanceof Wrapper &&
                                                mergedWalk.getOperators().get(k).equals(wrapperWithIDleft)) {
                                            found = true;
                                            mergedWalk.getOperators().add(k-1,join);
                                        }
                                        else if (mergedWalk.getOperators().get(j) instanceof Wrapper &&
                                                mergedWalk.getOperators().get(j).equals(wrapperWithIDleft) &&
                                                mergedWalk.getOperators().get(k) instanceof Wrapper &&
                                                mergedWalk.getOperators().get(k).equals(w)) {
                                            found = true;
                                            mergedWalk.getOperators().add(k-1,join);
                                        }
                                    }
                                }
                            });
                        }
                    }
                    joined.add(mergedWalk);
                }

                //Update the graph replacing targetVertex._1 for joined
                boolean removed = partialWalksGraph.removeVertex(sourceVertex);
                Tuple2<String,Set<Walk>> newVertex = new Tuple2<String,Set<Walk>>(targetVertex._1,joined);
                partialWalksGraph.addVertex(newVertex);
                System.out.println("Add new vertex "+newVertex);
                Graphs.neighborListOf(partialWalksGraph, targetVertex).forEach(neighbor -> {
                    if (!sourceVertex.equals(neighbor)) {
                        partialWalksGraph.addEdge(newVertex, neighbor, UUID.randomUUID().toString());
                        /*try {
                            partialWalksGraph.addDagEdge(newVertex, neighbor, UUID.randomUUID().toString());
                        } catch (DirectedAcyclicGraph.CycleFoundException e) {
                            e.printStackTrace();
                        }*/
                    }
                });
                partialWalksGraph.removeVertex(targetVertex);

                //current = new Tuple2<>(next._1, joined);
            });
        });

        return res._2;
    }

}