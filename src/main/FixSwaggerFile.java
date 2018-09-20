package main;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

public class FixSwaggerFile {

	public static void main(String[] args) throws Exception {
		File swaggerFile = new File("obp_v3-1-0.json");
		new FixSwaggerFile(swaggerFile).run();
	}

	private Map<String, JsonObject> apis;
	private Gson gson;
	private File inputFile;
	private Set<String> missingTypes;
	private JsonObject obpFull;

	private File outputFolder;
	private Set<String> tags;
	private Map<String, Entry<String, JsonElement>> typeDefinitions;

	public FixSwaggerFile(File inputFile) {
		this.inputFile = inputFile;
		this.outputFolder = new File("out");
		this.gson = new GsonBuilder().setPrettyPrinting().create();
	}

	private void addTypeDefinitionsFromAPI(JsonObject root) {
		typeDefinitions = new HashMap<>();
		Set<Entry<String, JsonElement>> set = root.getAsJsonObject("definitions").entrySet();
		for (Entry<String, JsonElement> entry : set) {
			typeDefinitions.put("#/definitions/" + entry.getKey(), entry);
		}
	}

	private void createAllApis() {
		extractTags();
		tags.forEach(t -> createApiForName(t));
	}

	private void createApiForName(String apiName) {
		System.out.println("API:" + apiName.toLowerCase());
		JsonObject api = new JsonObject();
		obpFull.entrySet().forEach(e -> {
			if ("paths".equals(e.getKey())) {
				api.add("paths", createPathsForAPI(new JsonPrimitive(apiName), e.getValue().getAsJsonObject()));
				return;
			}
			if ("basePath".equals(e.getKey())) {
				api.add(e.getKey(), new JsonPrimitive("/obp-" + apiName.toLowerCase()));
				return;
			}
			if ("info".equals(e.getKey())) {
				JsonObject info = shallowCopy(e.getValue().getAsJsonObject());
				info.add("title", new JsonPrimitive("OpenBankProject-" + apiName));
				api.add("info", info);
				return;
			}
			if ("definitions".equals(e.getKey())) {
				rebuildDefinitions(api);
				return;
			}
			api.add(e.getKey(), e.getValue());
		});
		apis.put(apiName, api);
	}

	private JsonObject shallowCopy(JsonObject o) {
		JsonObject copy = new JsonObject();
		o.entrySet().forEach(a -> copy.add(a.getKey(), a.getValue()));
		return copy;
	}

	private JsonObject createPathsForAPI(JsonPrimitive apiName, JsonObject paths) {
		JsonArray apiTags = new JsonArray();
		apiTags.add(apiName);

		JsonObject newPaths = new JsonObject();

		for (Entry<String, JsonElement> pathEntry : paths.entrySet()) {
			JsonObject path = pathEntry.getValue().getAsJsonObject();
			for (Entry<String, JsonElement> methodEntry : path.entrySet()) {
				JsonObject method = methodEntry.getValue().getAsJsonObject();

				if (isMethodTaggedForAPI(method, apiName)) {
					String summary = methodEntry.getValue()
												.getAsJsonObject()
												.get("summary")
												.getAsJsonPrimitive()
												.getAsString();
					System.out.println("  * " + summary);
					System.out.println("    " + methodEntry.getKey().toUpperCase() + " " + pathEntry.getKey());
					JsonObject newMethod = new JsonObject();
					method.entrySet().forEach(e -> newMethod.add(e.getKey(), e.getValue()));
					newMethod.add("tags", apiTags);
					getOrCreateObject(newPaths, pathEntry.getKey()).add(methodEntry.getKey(), newMethod);
				}
			}
		}
		return newPaths;
	}

	private void displayMissingTypeDefinitions() {
		if (!missingTypes.isEmpty()) {
			System.out.println("Missing Types in Open Bank API Swagger File");
			for (String type : missingTypes) {
				System.out.println(" - " + type);
			}
		}
	}

	private void extractTagNames(JsonElement container) {
		if (container.isJsonArray()) {
			container.getAsJsonArray().forEach(tag -> tags.add(tag.getAsString()));
		}
	}

	private void extractTags() {
		tags = new HashSet<>();
		JsonObject paths = obpFull.getAsJsonObject("paths");
		forEach(paths, p -> forEach(p, methods -> forEach(methods, "tags", this::extractTagNames)));
	}

	private void fixApiTitle(String title) {
		obpFull.getAsJsonObject("info").addProperty("title", title);
	}

	public void forEach(JsonElement element, Consumer<JsonElement> c) {
		if (element.isJsonObject()) {
			Set<Entry<String, JsonElement>> set = element.getAsJsonObject().entrySet();
			set.forEach(entry -> c.accept(entry.getValue()));
		}
	}

	public void forEach(JsonElement element, String key, Consumer<JsonElement> c) {
		if (element.isJsonObject()) {
			Set<Entry<String, JsonElement>> set = element.getAsJsonObject().entrySet();
			set.stream().filter(e -> key.equals(e.getKey())).forEach(e -> c.accept(e.getValue()));
		}
	}

	public JsonObject getOrCreateObject(JsonObject container, String child) {
		if (container.has(child)) {
			return container.get(child).getAsJsonObject();
		}
		JsonObject object = new JsonObject();
		container.add(child, object);
		return object;
	}

	private void initialize() {
		apis = new HashMap<>();
		missingTypes = new HashSet<>();

		if (!outputFolder.exists()) {
			outputFolder.mkdirs();
		}
	}

	public boolean isMethodTaggedForAPI(JsonObject method, JsonPrimitive apiName) {
		if (method.has("tags")) {
			return method.getAsJsonArray("tags").contains(apiName);
		}
		return false;
	}

	private JsonObject readInputFile() throws IOException, FileNotFoundException {
		try (JsonReader reader = new JsonReader(new FileReader(inputFile))) {
			return gson.fromJson(reader, JsonObject.class);
		}
	}

	private void rebuildDefinitions(JsonObject api) {
		System.out.println("rebuild definitions");
		JsonObject definitions = new JsonObject();
		resolveReferences(definitions, api);
		api.add("definitions", definitions);
	}

	private void resolveReferences(JsonObject definitions, JsonArray array) {
		for (JsonElement entry : array) {
			if (entry.isJsonObject()) {
				resolveReferences(definitions, entry.getAsJsonObject());
			}
			if (entry.isJsonArray()) {
				resolveReferences(definitions, entry.getAsJsonArray());
			}
		}
	}

	private void resolveReferences(JsonObject definitions, JsonObject object) {
		Set<Entry<String, JsonElement>> set = object.entrySet();
		for (Entry<String, JsonElement> entry : set) {
			if ("$ref".equals(entry.getKey())) {
				Entry<String, JsonElement> type = typeDefinitions.get(entry.getValue().getAsString());
				if (type != null) {
					if (definitions.has(type.getKey())) {
						continue;
					}
					definitions.add(type.getKey(), type.getValue().getAsJsonObject());
					resolveReferences(definitions, type.getValue().getAsJsonObject());
				} else {
					System.out.println("!missing type:" + entry.getValue().getAsString());
					missingTypes.add(entry.getValue().getAsString());
				}
			} else {
				if (entry.getValue().isJsonObject()) {
					resolveReferences(definitions, entry.getValue().getAsJsonObject());
				}
				if (entry.getValue().isJsonArray()) {
					resolveReferences(definitions, entry.getValue().getAsJsonArray());
				}
			}
		}
	}

	public void run() throws IOException {
		initialize();

		obpFull = readInputFile();

		addTypeDefinitionsFromAPI(obpFull);

		fixApiTitle("OpenBankProjectAPI");

		writeObjectToFile(obpFull, "obp-full.json");

		createAllApis();

		writeAllApis();

		displayMissingTypeDefinitions();

	}

	private void writeAllApis() throws IOException {
		Set<Entry<String, JsonObject>> entrySet = apis.entrySet();

		for (Entry<String, JsonObject> entry : entrySet) {
			writeObjectToFile(entry.getValue(), "obp-" + entry.getKey().toLowerCase() + ".json");
		}
	}

	public void writeObjectToFile(JsonObject object, String pathname) throws IOException {
		try (JsonWriter jsonWriter = new JsonWriter(new FileWriter(new File(outputFolder, pathname)))) {
			jsonWriter.setIndent("  ");
			gson.toJson(object, jsonWriter);
			jsonWriter.flush();
		}
	}
}
