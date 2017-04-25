package controllers;

import play.Configuration;
import play.Environment;
import play.mvc.Result;
import play.twirl.api.Html;

import javax.inject.Inject;
import java.io.IOException;

public class Indexer extends OERWorldMap {

  @Inject
  public Indexer(Configuration aConf, Environment aEnv) {
    super(aConf, aEnv);
  }

  public static Result index() throws IOException {

    // TODO: implement indexing
    return ok(new Html("OER World Map : Indexing OK"));

  }

}
