package com.xiaoji.duan.nautilus.core;

import java.io.File;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.github.GHApp;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHAppInstallationToken;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHPermissionType;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.PagedIterable;
import org.kohsuke.github.extras.OkHttpConnector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.squareup.okhttp.Cache;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.OkUrlFactory;
import com.xiaoji.duan.nautilus.core.github.GitHubUtils;

/**
 * Github API lib homepage
 * 
 * https://github-api.kohsuke.org/
 */
import io.vertx.amqpbridge.AmqpBridge;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.multipart.MultipartForm;

public class MainVerticle extends AbstractVerticle {

	private AmqpBridge local = null;
	private AmqpBridge remote = null;
	private WebClient client = null;
	
	private Cache cache = null;
	private String jwtToken = "";
	private GitHub githubAuthAsInst = null;

	private ObjectMapper MAPPER = null;
	
	@Override
	public void start(Promise<Void> startPromise) throws Exception {
		vertx.exceptionHandler(exception -> {
			error("Vertx exception caught.");
			System.exit(-1);
		});

		MAPPER = new ObjectMapper(new YAMLFactory());
		
		cache = new Cache(new File(config().getString("cachedir", "D:/caches")), 10 * 1024 * 1024); // 10MB cache
		jwtToken = GitHubUtils.createJWT(String.valueOf(51361), config().getString("privatekey", "D:/xiaojiwork/nautilus-core/src/main/resources/nautilus-cs.2020-01-19.private-key.der"), 600000);
		githubAuthAsInst = new GitHubBuilder()
				.fromEnvironment()
				.withConnector(new OkHttpConnector(new OkUrlFactory(new OkHttpClient().setCache(cache))))
				.withJwtToken(jwtToken)
                .build();

		client = WebClient.create(vertx);

		this.registerCoreTasks();
		
//		local = AmqpBridge.create(vertx);
//		connectLocalServer();
//
//		AmqpBridgeOptions remoteOption = new AmqpBridgeOptions();
//		remoteOption.setReconnectAttempts(60);			// 重新连接尝试60次
//		remoteOption.setReconnectInterval(60 * 1000);	// 每次尝试间隔1分钟
//		
//		remote = AmqpBridge.create(vertx, remoteOption);
//		connectRemoteServer();

		Router router = Router.router(vertx);

		Set<HttpMethod> allowedMethods = new HashSet<HttpMethod>();
		allowedMethods.add(HttpMethod.OPTIONS);
		allowedMethods.add(HttpMethod.GET);
		allowedMethods.add(HttpMethod.POST);
		allowedMethods.add(HttpMethod.PUT);
		allowedMethods.add(HttpMethod.DELETE);
		allowedMethods.add(HttpMethod.CONNECT);
		allowedMethods.add(HttpMethod.PATCH);
		allowedMethods.add(HttpMethod.HEAD);
		allowedMethods.add(HttpMethod.TRACE);

		router.route().handler(CorsHandler.create("*")
				.allowedMethods(allowedMethods)
				.allowedHeader("*")
				.allowedHeader("Content-Type")
				.allowedHeader("lt")
				.allowedHeader("pi")
				.allowedHeader("pv")
				.allowedHeader("di")
				.allowedHeader("dt")
				.allowedHeader("ai"));

		StaticHandler staticfiles = StaticHandler.create().setCachingEnabled(false).setWebRoot("static");
		router.route("/nautilus/static/*").handler(staticfiles);
		router.route("/nautilus").pathRegex("\\/.+\\.json").handler(staticfiles);

		router.route("/nautilus/:repo/activation").handler(BodyHandler.create());
		router.route("/nautilus/:repo/activation").produces("application/json").handler(this::activation);

		vertx.createHttpServer().requestHandler(router::accept).listen(config().getInteger("port", 8080), http -> {
			if (http.succeeded()) {
				startPromise.complete();
				System.out.println("HTTP server started on port " + config().getInteger("port", 8080));
			} else {
				startPromise.fail(http.cause());
			}
		});
	}

	private void connectLocalServer() {
		local.start(config().getString("local.server.host", "sa-amq"), config().getInteger("local.server.port", 5672),
				res -> {
					if (res.failed()) {
						res.cause().printStackTrace();
						connectLocalServer();
					} else {
						info("Local stomp server connected.");

					}
				});
	}
	
	private void registerCoreTasks() {
		String url = config().getString("tasks.register", "http://sa-cdc:8080/cdc/mwxing_register_tasks_start/json/trigger");
		HttpRequest<Buffer> post = client.postAbs(url);
		
		post.sendJsonObject(this.getNautilusIntegrationInstallationTask(), handler -> {});
		post.sendJsonObject(this.getNautilusInstallationTask(), handler -> {});
		post.sendJsonObject(this.getNautilusPushTask(), handler -> {});
	}
	
	private JsonObject getFilter(String name, Object value) {
		return new JsonObject().put("name", name).put("value", value);
	}
	
	private JsonObject getNautilusPushTask() {
		JsonObject taskRunAt = new JsonObject()
				.put("eventId", "GITHUB_APP")
				.put("filters", new JsonArray()
						.add(this.getFilter("webhook", "github_app"))
						.add(this.getFilter("event", "push"))
						.add(this.getFilter("observer", "43193b8cbd26b614fbc4b4885a9a512740a2df35")));
		JsonObject taskRunWith = new JsonObject()
				.put("url", "https://pluto.guobaa.com/cdc/nautilus_github_app_push/json/trigger")
				.put("payload", new JsonObject().put("github_app", config().getJsonObject("github.app")));
		
		return new JsonObject()
				.put("saName", "Nautilus App Event Data Receiver")
				.put("saPrefix", "cdc")
				.put("taskId", "github_app_nautilus_push")
				.put("taskType", "GITHUB_APP")
				.put("taskName", "GitHub App Push")
				.put("taskRunAt", taskRunAt.encode())
				.put("taskRunWith", taskRunWith.encode());
	}
	
	private JsonObject getNautilusInstallationTask() {
		JsonObject taskRunAt = new JsonObject()
				.put("eventId", "GITHUB_APP")
				.put("filters", new JsonArray()
						.add(this.getFilter("webhook", "github_app"))
						.add(this.getFilter("event", "installation"))
						.add(this.getFilter("observer", "43193b8cbd26b614fbc4b4885a9a512740a2df35")));
		JsonObject taskRunWith = new JsonObject()
				.put("url", "https://pluto.guobaa.com/cdc/nautilus_github_app_install/json/trigger")
				.put("payload", new JsonObject().put("github_app", config().getJsonObject("github.app")));
		
		return new JsonObject()
				.put("saName", "Nautilus App Event Data Receiver")
				.put("saPrefix", "cdc")
				.put("taskId", "github_app_nautilus_core_installation")
				.put("taskType", "GITHUB_APP")
				.put("taskName", "GitHub App Installation")
				.put("taskRunAt", taskRunAt.encode())
				.put("taskRunWith", taskRunWith.encode());
	}
	
	private JsonObject getNautilusIntegrationInstallationTask() {
		JsonObject taskRunAt = new JsonObject()
				.put("eventId", "GITHUB_APP")
				.put("filters", new JsonArray()
						.add(this.getFilter("webhook", "github_app"))
						.add(this.getFilter("event", "integration_installation"))
						.add(this.getFilter("observer", "43193b8cbd26b614fbc4b4885a9a512740a2df35")));
		JsonObject taskRunWith = new JsonObject()
				.put("url", "https://pluto.guobaa.com/cdc/nautilus_github_app_install/json/trigger")
				.put("payload", new JsonObject().put("github_app", config().getJsonObject("github.app")));
		
		return new JsonObject()
				.put("saName", "Nautilus App Event Data Receiver")
				.put("saPrefix", "cdc")
				.put("taskId", "github_app_nautilus_core_integration_installation")
				.put("taskType", "GITHUB_APP")
				.put("taskName", "GitHub App Integration Installation")
				.put("taskRunAt", taskRunAt.encode())
				.put("taskRunWith", taskRunWith.encode());
	}
	
	private void connectRemoteServer() {
		remote.start(config().getString("remote.server.host", "sa-amq"), config().getInteger("remote.server.port", 5672),
				res -> {
					if (res.failed()) {
						res.cause().printStackTrace();
						connectRemoteServer();
					} else {
						info("Remote stomp server connected.");

					}
				});
	}

	private void info(String log) {
		if (config().getBoolean("log.info", Boolean.FALSE)) {
			System.out.println(log);
		}
	}

	private void debug(String log) {
		if (config().getBoolean("log.debug", Boolean.FALSE)) {
			System.out.println(log);
		}
	}

	private void error(String log) {
		if (config().getBoolean("log.error", Boolean.TRUE)) {
			System.out.println(log);
		}
	}
	
	private void activation(RoutingContext ctx) {
		String reponame = ctx.pathParam("repo");
		reponame = URLDecoder.decode(reponame);
		System.out.println("Repository " + reponame);
		
		JsonObject body = ctx.getBodyAsJson();
		JsonObject payload = body.getJsonObject("event", new JsonObject())
				.getJsonObject("output", new JsonObject())
				.getJsonObject("payload", new JsonObject());
		
		String ref = payload.getString("ref");
		Long id = payload.getJsonObject("installation", new JsonObject()).getLong("id");
		Long repoid = payload.getJsonObject("repository", new JsonObject()).getLong("id");
		String fullname = payload.getJsonObject("repository", new JsonObject()).getString("full_name");
		JsonArray modified = payload.getJsonObject("head_commit", new JsonObject()).getJsonArray("modified", new JsonArray());
		
		if (ref == null || id == null || repoid == null || fullname == null) {
			System.out.println("one of items [ref, id, repoid, fullname] has null value.");
			return;
		}

		System.out.println("activation with [ref, id, repoid, fullname] " + ref + ", " + id + ", " + repoid + ", " + fullname + ".");
		vertx.executeBlocking(go -> {
			try {

				GHApp app = null;
				
				try {
					app = githubAuthAsInst.getApp();
				} catch (GHFileNotFoundException expired) {
					jwtToken = GitHubUtils.createJWT(String.valueOf(51361), config().getString("privatekey", "D:/xiaojiwork/nautilus-core/src/main/resources/nautilus-cs.2020-01-19.private-key.der"), 600000);
					githubAuthAsInst = new GitHubBuilder()
							.fromEnvironment()
							.withConnector(new OkHttpConnector(new OkUrlFactory(new OkHttpClient().setCache(cache))))
							.withJwtToken(jwtToken)
			                .build();

					app = githubAuthAsInst.getApp();
				}
				
				GHAppInstallation installation = app.getInstallationById(id);
				
				GHUser user = installation.getAccount();
				String login = user.getLogin();
		
				Map<String, GHPermissionType> permissions = new HashMap<String, GHPermissionType>();
				permissions.put("contents", GHPermissionType.READ);
				
				List<Long> repositoryIds = new ArrayList<Long>();
				repositoryIds.add(repoid);
				
				GHAppInstallationToken dd= installation
						.createToken(permissions)
						.repositoryIds(repositoryIds)
						.create();
				String token = dd.getToken();
				System.out.println(token);
		
				// 建立指定用户的访问
				GitHub root = new GitHubBuilder()
						.fromEnvironment()
						.withConnector(new OkHttpConnector(new OkUrlFactory(new OkHttpClient().setCache(cache))))
						.withOAuthToken(token, login)
						.build();
				
				GHRepository repo = root.getRepository(fullname);
				
				try {
					GHContent content = repo.getFileContent(".nautilus.yml", ref);
					String nautilus = content.getContent();
		
					System.out.println(nautilus);
					System.out.println(".nautilus.yml readed");
					
					Object node = MAPPER.readValue(nautilus, Object.class);
					
					ObjectMapper jsonWriter = new ObjectMapper();
					String json = jsonWriter.writeValueAsString(node);
					
					JsonObject jn = new JsonObject(json);
					
					System.out.println(jn.encodePrettily());
					
					// 判断是否修改需要触发发布
					error("modified " + modified.size() + " files.");
					Iterator list = modified.iterator();
					Boolean isDeploy = false;
					Boolean forceDeploy = jn.getJsonObject("deploy", new JsonObject()).getBoolean("force", Boolean.FALSE);
					
					while(list.hasNext()) {
						String path = (String) list.next();
						error("confirm " + path);
						if (".nautilus.yml".equals(path)) {
							isDeploy = true;
							break;
						}
					}
					
					if (isDeploy || forceDeploy) {
						this.processExecutors(id, jn.getJsonArray("executors", new JsonArray()));
						this.processFlows(id, jn.getJsonArray("flows", new JsonArray()));
						this.processDispatchEvents(id, jn.getJsonObject("dispatch", new JsonObject()).getJsonArray("events", new JsonArray()));
					}
					
				} catch (GHFileNotFoundException notfound) {
					System.out.println("file .nautilus.yml not found.");
				}
				
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} //sdk-github-api-app-test
		
		}, handler -> {});

	}
	
	private void processDispatchEvents(Long id, JsonArray events) {
		error("Dispatch events for " + id);
		Iterator iterators = events.iterator();

		while(iterators.hasNext()) {
			Object next = iterators.next();
			
			if (next instanceof String) {
				error((String) next);
				error("Wrong format, skipped!");
				continue;
			}
			
			JsonObject event = (JsonObject) next;
			error(event.encodePrettily());
			
			if (!event.containsKey("name") || !(event.getValue("name") instanceof String)) {
				error("Need [name:String] property, skipped!");
				continue;
			}
			
			if (!event.containsKey("filters") || !(event.getValue("filters") instanceof JsonArray) || event.getJsonArray("filters").size() < 1) {
				error("Need [filters:JsonArray] property or size is zero, skipped!");
				continue;
			}

			JsonArray filters = event.getJsonArray("filters");
			boolean isFiltersOk = true;
			
			for (int i = 0; i < filters.size(); i ++) {
				Object filter = filters.getValue(i);
				
				if (!(filter instanceof JsonObject)) {
					isFiltersOk = false;
					break;
				}
				
				Object name = ((JsonObject) filter).getValue("name");
				Object value = ((JsonObject) filter).getValue("value");
				
				if (!(name instanceof String) || StringUtils.isEmpty((String) name)) {
					isFiltersOk = false;
					break;
				}

				if (!(value instanceof String) || StringUtils.isEmpty((String) value)) {
					isFiltersOk = false;
					break;
				}
			}
			
			if (!isFiltersOk) {
				error("Need [filters -> filter:JsonObject] property or [name:String]/[value:String] is empty, skipped!");
				continue;
			}
			
			if (!event.containsKey("flow") || !(event.getValue("flow") instanceof String)) {
				error("Need [flow] property, skipped!");
				continue;
			}
			
			if (!event.containsKey("type") || !(event.getValue("type") instanceof String)) {
				error("Need [type] property, skipped!");
				continue;
			}
			
			if (!event.containsKey("id") || !(event.getValue("id") instanceof String)) {
				error("Need [id] property, skipped!");
				continue;
			}
			
			JsonObject taskRunAt = new JsonObject()
					.put("eventId", event.getString("event"))
					.put("filters", event.getJsonArray("filters"));
			JsonObject taskRunWith = new JsonObject()
					.put("url", "https://pluto.guobaa.com/cdc/" + event.getString("flow") + "/json/trigger")
					.put("payload", new JsonObject().put("github_app", config().getJsonObject("github.app")));
			
			JsonObject task = new JsonObject()
					.put("saName", event.getString("name", "Nautilus App Register Task"))
					.put("saPrefix", "cdc")
					.put("taskId", event.getString("id"))
					.put("taskType", event.getString("type"))
					.put("taskName", event.getString("name", "Nautilus App Register Task"))
					.put("taskRunAt", taskRunAt.encode())
					.put("taskRunWith", taskRunWith.encode());
			
			deployEventTask(id, task);
		}
	}
	
	private void deployEventTask(Long id, JsonObject task) {
		String url = config().getString("tasks.register", "http://sa-cdc:8080/cdc/mwxing_register_tasks_start/json/trigger");
		HttpRequest<Buffer> post = client.postAbs(url);
		
		post.sendJsonObject(task, handler -> {});
	}
	
	private void processFlows(Long id, JsonArray flows) {
		error("flow process for " + id);
		Iterator iterators = flows.iterator();
		
		while(iterators.hasNext()) {
			Object next = iterators.next();
			
			if (next instanceof String) {
				continue;
			}
			
			JsonObject flow = (JsonObject) next;
			
			if (flow.fieldNames().size() == 1) {
				String trigger = flow.fieldNames().iterator().next();
				
				if (flow.getValue(trigger) instanceof JsonObject) {
					flow = flow.getJsonObject(trigger);
					if (!flow.containsKey("trigger")) {
						flow.put("trigger", trigger);
					}
				}
			}
			
			if (!flow.containsKey("name") || !(flow.getValue("name") instanceof String)) {
				continue;
			}
			
			error("name : " + flow.getString("name"));
			
			if (!flow.containsKey("trigger") || !(flow.getValue("trigger") instanceof String)) {
				continue;
			}

			error("trigger : " + flow.getString("trigger"));
			
			if (!flow.containsKey("parameters") || !(flow.getValue("parameters") instanceof JsonArray)) {
				continue;
			}

			if (!flow.containsKey("follows") || !(flow.getValue("follows") instanceof JsonArray)) {
				continue;
			}

			deployFlow(id, flow);
		}
	}
	
	private void processExecutors(Long id, JsonArray executors) {
		error("executor process for " + id);
		Iterator iterators = executors.iterator();
		
		while(iterators.hasNext()) {
			Object next = iterators.next();
			
			if (next instanceof String) {
				continue;
			}
			
			JsonObject executor = (JsonObject) next;
			
			if (executor.fieldNames().size() == 1) {
				String executorid = executor.fieldNames().iterator().next();
				
				if (executor.getValue(executorid) instanceof JsonObject) {
					executor = executor.getJsonObject(executorid);
					if (!executor.containsKey("id")) {
						executor.put("id", executorid);
					}
				}
			}

			if (!executor.containsKey("id") || !(executor.getValue("id") instanceof String)) {
				continue;
			}
			
			error("id : " + executor.getString("id"));
			
			if (!executor.containsKey("container") || !"acj".equals(executor.getString("container"))) {
				continue;
			}
			
			error("container : " + executor.getString("container"));
			
			if (!executor.containsKey("scriptengine") || !"Nashorn".equals(executor.getString("scriptengine"))) {
				continue;
			}
			
			error("scriptengine : " + executor.getString("scriptengine"));
			
			if (!executor.containsKey("clean") && !executor.containsKey("shouldclean")) {
				continue;
			}
			
			deployNashorn(id, executor);
		}
	}
	
	private void deployNashorn(Long id, JsonObject nashorn) {
		
		String tempdir = UUID.randomUUID().toString();
		
		List<Future<JsonObject>> compositeFutures = new LinkedList<>();
		
		if (nashorn.containsKey("clean")) {
			String clean = nashorn.getString("clean");
			String filename = "clean.js";
			String filepath = "/tmp/" + tempdir;
			
			Future<JsonObject> cleandatafuture = Future.future();
			compositeFutures.add(cleandatafuture);
			
			Future<String> cleanfuture = Future.future();
			
			savefile(cleanfuture, clean, filename, filepath);
			
			cleanfuture.setHandler(cleanhandler -> {
				if (cleanhandler.succeeded()) {
					String file = cleanhandler.result();
					
					upload(cleandatafuture, filename, file, "text/javascript", 1);
				} else {
					cleandatafuture.fail(cleanhandler.cause());
				}
			});
		}
		
		if (nashorn.containsKey("shouldclean")) {
			String shouldclean = nashorn.getString("shouldclean");
			String filename = "shouldclean.js";
			String filepath = "/tmp/" + tempdir;

			Future<JsonObject> shouldcleandatafuture = Future.future();
			compositeFutures.add(shouldcleandatafuture);
			
			Future<String> shouldcleanfuture = Future.future();
			
			savefile(shouldcleanfuture, shouldclean, filename, filepath);
			
			shouldcleanfuture.setHandler(shouldcleanhandler -> {
				if (shouldcleanhandler.succeeded()) {
					String file = shouldcleanhandler.result();
					
					upload(shouldcleandatafuture, filename, file, "text/javascript", 1);
				} else {
					shouldcleandatafuture.fail(shouldcleanhandler.cause());
				}
			});
		}
		
		CompositeFuture.all(Arrays.asList(compositeFutures.toArray(new Future[compositeFutures.size()])))
		.map(v -> compositeFutures.stream().map(Future::result).collect(Collectors.toList()))
		.setHandler(handler -> {
			if (handler.succeeded()) {
				List<JsonObject> jsfiles = handler.result();
				
				for (JsonObject jsfile : jsfiles) {
					if ("clean.js".equals(jsfile.getString("filename", ""))) {
						nashorn.put("clean-file", jsfile);
						nashorn.remove("clean");
					}

					if ("shouldclean.js".equals(jsfile.getString("filename", ""))) {
						nashorn.put("shouldclean-file", jsfile);
						nashorn.remove("shouldclean");
					}
				}
				
				error("+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++");
				error(nashorn.encodePrettily());
				error("+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++");
				
				HttpRequest<Buffer> request = client.postAbs("http://sa-cdc:8080/cdc/nautilus_deploy_nashorn_starter/json/trigger");

				JsonObject deploy = new JsonObject()
						.put("owner", String.valueOf(id))
						.put("nashorn", nashorn);
				
				request.sendJsonObject(deploy, reqhandler -> {
					if (reqhandler.succeeded()) {
						info(String.valueOf(id) + "'s nashorn deploy request sent.");
					} else {
						error(reqhandler.cause().getMessage());
					}
				});
			} else {
				error(handler.cause().getMessage());
			}
		});
	}
	
	private void deployFlow(Long id, JsonObject flow) {
		HttpRequest<Buffer> request = client.postAbs("http://sa-cdc:8080/cdc/nautilus_deploy_flow_starter/json/trigger");

		JsonObject deploy = new JsonObject()
				.put("owner", String.valueOf(id))
				.put("flow", flow);
		
		request.sendJsonObject(deploy, handler -> {
			if (handler.succeeded()) {
				info(String.valueOf(id) + "'s flow deploy request sent.");
			} else {
				error(handler.cause().getMessage());
			}
		});
	}

	private void savefile(Future<String> future, String content, String filename, String filepath) {
		String file = filepath + "/" + filename;

		Future<String> existsdirfuture = Future.future();
		
		// 确认目录是否存在,不存在则创建
		vertx.fileSystem().exists(filepath, dirhandler -> {
			if (dirhandler.succeeded()) {
				Boolean direxists = dirhandler.result();
				
				Future<String> dirfuture = Future.future();
				
				if (!direxists) {
					vertx.fileSystem().mkdirs(filepath, mkdirshandler -> {
						if (mkdirshandler.succeeded()) {
							dirfuture.complete(filepath);
						} else {
							dirfuture.fail(mkdirshandler.cause());
						}
					});
				} else {
					dirfuture.complete(filepath);
				}
				
				dirfuture.setHandler(checkdirshandler -> {
					if (checkdirshandler.succeeded()) {
						existsdirfuture.complete();
					} else {
						existsdirfuture.fail(checkdirshandler.cause());
					}
				});
			} else {
				existsdirfuture.fail(dirhandler.cause());
			}
		});
		
		// 保存到文件
		existsdirfuture.setHandler(existsdirhandler -> {
			if (existsdirhandler.succeeded()) {
				vertx.fileSystem().writeFile(file, Buffer.buffer(content), writefilehandler -> {
					if (writefilehandler.succeeded()) {
						future.complete(file);
					} else {
						future.fail(writefilehandler.cause());
					}
				});
			} else {
				future.fail(existsdirhandler.cause());
			}
		});
	}
	
	private void upload(Future<JsonObject> future, String filename, String filepath, String filetype, Integer retry) {
		MultipartForm form = MultipartForm.create()
				.attribute("saPrefix", "nau")
				.attribute("group", "xiaoji")
				.attribute("username", "group")
				.textFileUpload("file", filename, filepath, filetype);
		
		this.client.post(8080, "sa-abl", "/abl/store/remote/upload")
		.sendMultipartForm(form, ar -> {
			if (ar.succeeded()) {
				HttpResponse<Buffer> response = ar.result();
				
				System.out.println("File " + filename + " upload result [" + response.bodyAsString() + "]");
				
				Integer fileid = response.bodyAsJsonObject().getInteger("data");
				
				future.complete(new JsonObject()
						.put("uri", "http://sa-abl:8080/abl/store/local/getContent/" + fileid)
						.put("filename", filename)
						.put("filetype", filetype)
						.put("remoteid", fileid));
			} else {
				if (retry > 3) {
					System.out.println("Upload retried 3 times with follow error:");
					ar.cause().printStackTrace();
				} else {
					upload(future, filename, filepath, filetype, retry + 1);
				}
			}
		});

	}

	public static void main(String[] args) {
	try {
		Cache cache = new Cache(new File("D:/caches"), 10 * 1024 * 1024); // 10MB cache

		String jwtToken = GitHubUtils.createJWT(String.valueOf(51361), "D:/xiaojiwork/nautilus-core/src/main/resources/nautilus-cs.2020-01-19.private-key.der", 600000);

		GitHub githubAuthAsInst = new GitHubBuilder()
				.fromEnvironment()
				.withConnector(new OkHttpConnector(new OkUrlFactory(new OkHttpClient().setCache(cache))))
				.withJwtToken(jwtToken)
                .build();
				
//		List<String> scopes = new ArrayList<String>();
//		scopes.add("public_repo");
//		
//		GHAuthorization auth = githubAuthAsInst.createOrGetAuth("Iv1.91d7e63870e8a391", "5e694318ec6ddb407135ff078c5519ae440ae05e", scopes, "test", "");
//		String token = auth.getToken();
//		System.out.println(token);
		GHApp app = githubAuthAsInst.getApp();
		System.out.println(app.getName());
		System.out.println(app.getInstallationsCount());
		PagedIterable<GHAppInstallation> installs = app.listInstallations();
		
		Iterator it = installs.iterator();
		
		while (it.hasNext()) {
			GHAppInstallation install = (GHAppInstallation) it.next();
			GHUser user = install.getAccount();
			System.out.println(user.getLogin());

			String login = user.getLogin();

			if ("XJ-GTD".equals(login)) {
				Map<String, GHPermissionType> permissions = new HashMap<String, GHPermissionType>();
				permissions.put("contents", GHPermissionType.READ);
				
				List<Long> repositoryIds = new ArrayList<Long>();
				repositoryIds.add(134405852L);
				
				GHAppInstallationToken dd= install
						.createToken(permissions)
						.repositoryIds(repositoryIds)
						.create();
				String token = dd.getToken();
				System.out.println(token);

				// 建立指定用户的访问
				GitHub root = new GitHubBuilder()
						.fromEnvironment()
						.withConnector(new OkHttpConnector(new OkUrlFactory(new OkHttpClient().setCache(cache))))
						.withOAuthToken(token, login)
						.build();
				
				List<GHRepository> listrepo = dd.getRepositories();
				System.out.println("+++++++++++++++++++++++++++++++++++++++++++");

				for (GHRepository repo : listrepo) {
					String fullname = repo.getFullName();
					System.out.println(fullname);
					
					if (!"XJ-GTD/GTD2".equals(fullname)) {
						System.out.println("continue");
						continue;
					}
					
					GHRepository repo1 = root.getRepository(fullname);
					try {
						GHContent content = repo1.getFileContent(".nautilus.yml", "refs/heads/cassiscornuta");
						String nautilus = content.getContent();
						System.out.println(nautilus);
						
						ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());
						
						Object node = MAPPER.readValue(nautilus, Object.class);
						
						ObjectMapper jsonWriter = new ObjectMapper();
						String json = jsonWriter.writeValueAsString(node);
						
						JsonObject jn = new JsonObject(json);
						
						System.out.println(jn.encodePrettily());
					} catch (GHFileNotFoundException notfound) {
						System.out.println("file not found.");
					}
					
					System.out.println("end of reading content.");
				}
				
			}
			
		}
		
		System.out.println(".nautilus.yml readed");
	} catch (Exception e) {
		// TODO Auto-generated catch block
		e.printStackTrace();
	} //sdk-github-api-app-test

}	
}
