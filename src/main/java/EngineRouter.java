import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jacamo.JacamoManager;
import jacamo.model.AgentDefinition;
import jacamo.model.AgentSource;
import jacamo.model.MasDefinition;

import java.util.*;

public class EngineRouter {

  private static final String APPLICATION_JSON = "application/json";

  private final JacamoManager jacamoManager;

  public EngineRouter(){
    jacamoManager = JacamoManager.getInstance();
  }

  public Router create(Vertx vertx){
    Router router = Router.router(vertx);
    router.route().handler(BodyHandler.create());
    router.get("/")
      .produces("text/html")
      .handler(ctx -> ctx.response().end("Hypermedia Agent Engine is running..."));

    //From now on only JSON endpoints
    router.route()
      .handler(ctx -> {
        ctx.response().putHeader("content-type", APPLICATION_JSON);
        ctx.next();
      });

    router.get("/agents")
      .produces(APPLICATION_JSON)
      .handler(this::handleGetAgentList);

    router.post("/agents")
      .consumes(APPLICATION_JSON)
      .produces(APPLICATION_JSON)
      .handler(this::handlePostAgent);

    router.get("/agents/:agentId")
      .produces(APPLICATION_JSON)
      .handler(this::handleGetAgent);

    router.get("/mas")
      .produces(APPLICATION_JSON)
      .handler(this::handleGetMasList);

    router.post("/mas")
      .consumes(APPLICATION_JSON)
      .produces(APPLICATION_JSON)
      .handler(this::handlePostMas);

    router.get("/mas/:masId")
      .produces(APPLICATION_JSON)
      .handler(this::handleGetMas);

    router.put("/mas/:masId/run")
      .produces(APPLICATION_JSON)
      .handler(this::handleRunMas);

    router.put("/mas/:masId/stop")
      .produces(APPLICATION_JSON)
      .handler(this::handleStopMas);

    return router;
  }

  private void handleGetAgentList(RoutingContext ctx) {
    List<String> agents = new ArrayList<>(jacamoManager.getStorage().getAvailableAgents());
    ctx.response().setStatusCode(HttpResponseStatus.OK.code())
      .end(new JsonObject()
        .put("agents", new JsonArray(agents))
        .encode()
      );
  }

  private void handlePostAgent(RoutingContext ctx) {
    JsonObject body = ctx.getBodyAsJson();
    String id = body.getString("id");
    String code = body.getString("code");
    String xml = body.getString("xml");
    AgentSource agent = new AgentSource(id, code);

    if(!jacamoManager.acceptAgentId(id)){
      ctx.response().setStatusCode(HttpResponseStatus.BAD_REQUEST.code()).end();
    }

    if(!jacamoManager.getStorage().saveAgent(agent)){
      ctx.response().setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR.code()).end();
    }

    ctx.response()
      .putHeader("Location", ctx.normalizedPath()+"/"+id)
      .setStatusCode(HttpResponseStatus.CREATED.code())
      .end(getAgentSourceDTO(agent, xml).encode());
  }

  private void handleGetAgent(RoutingContext ctx) {
    String id = ctx.pathParam("agentId");

    Optional<AgentSource> agent = jacamoManager.getStorage().getAgent(id);
    if(agent.isEmpty()){
        ctx.response().setStatusCode(HttpResponseStatus.NOT_FOUND.code()).end();
        return;
    }
    String xml = "<xml></xml>"; //TODO retrieve this

    ctx.response()
      .setStatusCode(HttpResponseStatus.OK.code())
      .end(getAgentSourceDTO(agent.get(), xml).encode());
  }

  private void handleGetMasList(RoutingContext ctx) {
    List<String> masList = new ArrayList<>(jacamoManager.getStorage().getAvailableMas());
    ctx.response().setStatusCode(HttpResponseStatus.OK.code())
      .end(new JsonObject()
        .put("mas", new JsonArray(masList))
        .encode()
      );
  }

  private void handlePostMas(RoutingContext ctx){
    JsonObject body = ctx.getBodyAsJson();
    String id = body.getString("id");
    JsonArray agentsArray = body.getJsonArray("agents");
    Set<AgentDefinition> agents = new HashSet<>();
    for (int i = 0; i < agentsArray.size(); i++) {
      JsonObject obj = agentsArray.getJsonObject(i);
      agents.add(new AgentDefinition(obj.getString("name"), obj.getString("type")));
    }
    if(agents.isEmpty()){
      ctx.response().setStatusCode(HttpResponseStatus.BAD_REQUEST.code()).end();
      return;
    }
    if(!jacamoManager.acceptMasId(id)){
      ctx.response().setStatusCode(HttpResponseStatus.BAD_REQUEST.code()).end();
      return;
    }

    MasDefinition newMas = new MasDefinition(id, agents);
    if(!this.jacamoManager.getStorage().saveMas(newMas)){
      ctx.response().setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR.code()).end();
      return;
    }
    ctx.response()
      .putHeader("Location", ctx.normalizedPath()+"/"+id)
      .setStatusCode(HttpResponseStatus.OK.code())
      .end(this.getMasDTO(newMas, false)
        .encode()
      );
  }

  private void handleGetMas(RoutingContext ctx) {
    String masId = ctx.pathParam("masId");

    Optional<MasDefinition> mas = jacamoManager.getStorage().getMas(masId);
    if(mas.isEmpty()){
      ctx.response().setStatusCode(HttpResponseStatus.NOT_FOUND.code()).end();
      return;
    }

    //TODO retrieve the running status from the JacamoManager
    boolean isRunning = false;

    ctx.response()
      .setStatusCode(HttpResponseStatus.OK.code())
      .end(this.getMasDTO(mas.get(), isRunning)
        .encode()
      );
  }

  private void handleRunMas(RoutingContext ctx) {
    if(this.jacamoManager.isMasRunning()){
      ctx.response().setStatusCode(HttpResponseStatus.FORBIDDEN.code()).end();
      return;
    }
    this.jacamoManager.runMas(ctx.pathParam("masId"));
    ctx.response().setStatusCode(HttpResponseStatus.OK.code()).end();
  }

  private void handleStopMas(RoutingContext ctx) {
    if(!this.jacamoManager.isMasRunning()){
      ctx.response().setStatusCode(HttpResponseStatus.FORBIDDEN.code()).end();
      return;
    }
    this.jacamoManager.stopMas(ctx.pathParam("masId"));
    ctx.response().setStatusCode(HttpResponseStatus.OK.code()).end();
  }

  private JsonObject getMasDTO(MasDefinition mas, boolean isRunning){
    JsonArray agents = new JsonArray();
    mas.getAgents().stream().map(this::getAgentDefinitionDTO).forEach(agents::add);
    return new JsonObject()
      .put("masId", mas.getId())
      .put("agents", agents)
      .put("running", isRunning);
  }

  private JsonObject getAgentDefinitionDTO(AgentDefinition agent){
    return new JsonObject()
      .put("name", agent.getName())
      .put("type", agent.getType());
  }

  private JsonObject getAgentSourceDTO(AgentSource agent, String xml){
    return new JsonObject()
      .put("agentName", agent.getId())
      .put("code", agent.getCode())
      .put("xml", xml);
  }


}