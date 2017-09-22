package services;

import org.apache.jena.atlas.RuntimeIOException;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.SimpleSelector;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.shared.Lock;

import java.io.IOException;
import java.io.InputStream;

/**
 * Created by fo on 27.04.16.
 */
public class InverseEnricher implements ResourceEnricher {

  private final Model mInverseRelations;
  private static final Property mInverseOf = ResourceFactory.createProperty("http://www.w3.org/2002/07/owl#inverseOf");

  public static final String CONSTRUCT_INVERSE =
    "CONSTRUCT {?o <%1$s> ?s} WHERE {" +
    "  ?s <%2$s> ?o ." +
    "}";


  public InverseEnricher() {

    this.mInverseRelations = ModelFactory.createDefaultModel();

    try (InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream("inverses.ttl")) {
      RDFDataMgr.read(mInverseRelations, inputStream, Lang.TURTLE);
    } catch (IOException e) {
      throw new RuntimeIOException(e);
    }

    StmtIterator it = mInverseRelations.listStatements(new SimpleSelector(null, mInverseOf, (RDFNode) null));
    Model inverseInverses = ModelFactory.createDefaultModel();
    while (it.hasNext()) {
      Statement stmt = it.next();
      inverseInverses.add(ResourceFactory.createStatement((Resource) stmt.getObject(), mInverseOf, stmt.getSubject()));
    }
    mInverseRelations.add(inverseInverses);

  }

  public void enrich(Model aToBeEnriched) {

    Model inverses = ModelFactory.createDefaultModel();

    mInverseRelations.enterCriticalSection(Lock.READ);
    aToBeEnriched.enterCriticalSection(Lock.READ);
    try {
      for (Statement stmt : mInverseRelations.listStatements().toList()) {
        String inferConstruct = String.format(CONSTRUCT_INVERSE, stmt.getSubject(), stmt.getObject());
        QueryExecutionFactory.create(QueryFactory.create(inferConstruct), aToBeEnriched).execConstruct(inverses);
      }
    } finally {
      aToBeEnriched.leaveCriticalSection();
      mInverseRelations.leaveCriticalSection();
    }

    aToBeEnriched.enterCriticalSection(Lock.WRITE);
    try {
      aToBeEnriched.add(inverses);
    } finally {
      aToBeEnriched.leaveCriticalSection();
    }

  }
}
