package net.kdt.pojavlaunch.modloaders.modpacks.api;

import android.content.Context;
import android.widget.Toast;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.LauncherPreferences;
import net.kdt.pojavlaunch.LauncherProfiles;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModrinthIndex;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchResult;
import net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper;
import net.kdt.pojavlaunch.utils.GsonJsonUtils;
import net.kdt.pojavlaunch.utils.ZipUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

public class ModrinthApi implements ModpackApi {
    private final ApiHandler mApiHandler;
    private volatile SearchFilters mCurrentFilters;

    public ModrinthApi() {
        mApiHandler = new ApiHandler("https://api.modrinth.com/v2");
    }

    @Override
    public SearchResult searchMod(SearchFilters searchFilters, SearchResult previousPageResult) {
        ModrinthSearchResult result = (ModrinthSearchResult) previousPageResult;
        if (result != null && result.previousOffset >= result.totalResultCount) {
            ModrinthSearchResult empty = new ModrinthSearchResult();
            empty.results = new ModItem[0];
            empty.totalResultCount = result.totalResultCount;
            empty.previousOffset = result.previousOffset;
            return empty;
        }

        mCurrentFilters = searchFilters;
        HashMap<String, Object> params = new HashMap<>();
        StringBuilder facets = new StringBuilder("[[\"project_type:")
                .append(searchFilters.isModpack ? "modpack" : "mod").append("\"]");
        if (searchFilters.mcVersion != null && !searchFilters.mcVersion.isEmpty()) {
            facets.append(",[\"versions:").append(searchFilters.mcVersion).append("\"]");
        }
        if (!searchFilters.isModpack && searchFilters.loader != null && !searchFilters.loader.isEmpty()) {
            facets.append(",[\"categories:").append(searchFilters.loader).append("\"]");
        }
        facets.append("]");
        params.put("facets", facets.toString());
        params.put("query", searchFilters.name == null ? "" : searchFilters.name);
        params.put("limit", 50);
        params.put("index", "relevance");
        if (result != null) params.put("offset", result.previousOffset);

        JsonObject response = mApiHandler.get("search", params, JsonObject.class);
        if (response == null || response.getAsJsonArray("hits") == null) return null;
        JsonArray hits = response.getAsJsonArray("hits");
        ModItem[] items = new ModItem[hits.size()];
        for (int i = 0; i < hits.size(); i++) {
            JsonObject hit = hits.get(i).getAsJsonObject();
            items[i] = new ModItem(Constants.SOURCE_MODRINTH,
                    "modpack".equals(hit.get("project_type").getAsString()),
                    hit.get("project_id").getAsString(),
                    hit.get("title").getAsString(),
                    hit.get("description").getAsString(),
                    getString(hit, "icon_url"));
        }
        if (result == null) result = new ModrinthSearchResult();
        result.previousOffset += hits.size();
        result.results = items;
        result.totalResultCount = response.get("total_hits").getAsInt();
        return result;
    }

    @Override
    public ModDetail getModDetails(ModItem item) {
        fillInMissingModItemData(item);
        HashMap<String, Object> params = new HashMap<>();
        SearchFilters filters = mCurrentFilters;
        if (filters != null) {
            if (filters.mcVersion != null && !filters.mcVersion.isEmpty())
                params.put("game_versions", "[\"" + filters.mcVersion + "\"]");
            if (filters.loader != null && !filters.loader.isEmpty())
                params.put("loaders", "[\"" + filters.loader + "\"]");
        }
        JsonArray response = params.isEmpty()
                ? mApiHandler.get("project/" + item.id + "/version", JsonArray.class)
                : mApiHandler.get("project/" + item.id + "/version", params, JsonArray.class);
        if (response == null || response.size() == 0) return null;

        List<String> names = new ArrayList<>(), ids = new ArrayList<>(), mcVersions = new ArrayList<>(), urls = new ArrayList<>(), hashes = new ArrayList<>();
        List<ModDetail.Dependencies[]> dependencies = new ArrayList<>();
        for (JsonElement element : response) {
            JsonObject version = element.getAsJsonObject();
            JsonArray gameVersions = version.getAsJsonArray("game_versions");
            JsonArray files = version.getAsJsonArray("files");
            if (gameVersions == null || gameVersions.size() == 0 || files == null || files.size() == 0) continue;
            JsonObject file = primaryFile(files);
            names.add(getString(version, "name"));
            ids.add(getString(version, "id"));
            mcVersions.add(gameVersions.get(0).getAsString());
            urls.add(getString(file, "url"));
            JsonObject fileHashes = file.getAsJsonObject("hashes");
            hashes.add(fileHashes == null ? null : getString(fileHashes, "sha1"));

            JsonArray depsJson = version.getAsJsonArray("dependencies");
            List<ModDetail.Dependencies> deps = new ArrayList<>();
            if (depsJson != null) {
                for (JsonElement depElement : depsJson) {
                    JsonObject dep = depElement.getAsJsonObject();
                    deps.add(new ModDetail.Dependencies(getString(dep, "project_id"),
                            getString(dep, "version_id"), getString(dep, "file_name"),
                            getString(dep, "dependency_type")));
                }
            }
            dependencies.add(deps.toArray(new ModDetail.Dependencies[0]));
        }
        if (names.isEmpty()) return null;
        return new ModDetail(item, names.toArray(new String[0]), ids.toArray(new String[0]),
                mcVersions.toArray(new String[0]), urls.toArray(new String[0]),
                hashes.toArray(new String[0]), dependencies.toArray(new ModDetail.Dependencies[0][]));
    }

    private void fillInMissingModItemData(ModItem item) {
        if (item.title != null && item.description != null && item.imageUrl != null) return;
        JsonObject project = mApiHandler.get("project/" + item.id, JsonObject.class);
        if (project == null) return;
        if (item.title == null) item.title = getString(project, "title");
        if (item.description == null) item.description = getString(project, "description");
        if (item.imageUrl == null) item.imageUrl = getString(project, "icon_url");
    }

    @Override
    public void handleInstallation(Context context, ModDetail detail, int selectedVersion) {
        if (detail == null || selectedVersion < 0 || selectedVersion >= detail.versionIds.length) return;
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.global_waiting);
        PojavApplication.sExecutorService.execute(() -> {
            try {
                File gameDir = getSelectedGameDirectory();
                File modsDir = new File(gameDir, "mods");
                if (!modsDir.exists() && !modsDir.mkdirs()) throw new IOException("Could not create mods folder");
                Set<String> installed = new HashSet<>();
                JsonObject selected = mApiHandler.get("version/" + detail.versionIds[selectedVersion], JsonObject.class);
                if (selected == null) throw new IOException("Could not retrieve the selected Modrinth version");
                installVersion(selected, modsDir, installed);

                ModDetail.Dependencies[] deps = detail.dependencies[selectedVersion];
                if (deps != null) {
                    for (ModDetail.Dependencies dependency : deps) {
                        if (!"required".equals(dependency.dependency_type)) continue;
                        JsonObject depVersion = null;
                        if (dependency.version_id != null && !dependency.version_id.isEmpty()) {
                            depVersion = mApiHandler.get("version/" + dependency.version_id, JsonObject.class);
                        } else if (dependency.project_id != null && !dependency.project_id.isEmpty()) {
                            HashMap<String, Object> query = new HashMap<>();
                            SearchFilters filters = mCurrentFilters;
                            if (filters != null && filters.loader != null)
                                query.put("loaders", "[\"" + filters.loader + "\"]");
                            if (filters != null && filters.mcVersion != null)
                                query.put("game_versions", "[\"" + filters.mcVersion + "\"]");
                            JsonArray versions = mApiHandler.get("project/" + dependency.project_id + "/version", query, JsonArray.class);
                            if (versions != null && versions.size() > 0) depVersion = versions.get(0).getAsJsonObject();
                        }
                        if (depVersion != null) installVersion(depVersion, modsDir, installed);
                    }
                }
                Tools.runOnUiThread(() -> Toast.makeText(context, "Mod installed to the selected profile", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                Tools.showErrorRemote(context, R.string.modpack_install_download_failed, e);
            } finally {
                ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
            }
        });
    }

    private File getSelectedGameDirectory() throws IOException {
        String selectedId = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
        LauncherProfiles.load();
        if (LauncherProfiles.mainProfileJson != null && LauncherProfiles.mainProfileJson.profiles != null) {
            MinecraftProfile profile = LauncherProfiles.mainProfileJson.profiles.get(selectedId);
            if (profile != null) return Tools.getGameDirPath(profile);
        }
        return new File(Tools.DIR_GAME_NEW);
    }

    private void installVersion(JsonObject version, File modsDir, Set<String> installed) throws IOException, NoSuchAlgorithmException {
        JsonArray files = version.getAsJsonArray("files");
        if (files == null || files.size() == 0) throw new IOException("Modrinth version has no downloadable files");
        JsonObject file = primaryFile(files);
        String url = getString(file, "url");
        String filename = getString(file, "filename");
        if (url == null || filename == null) throw new IOException("Modrinth file information is incomplete");
        if (!installed.add(filename)) return;
        JsonObject hashes = file.getAsJsonObject("hashes");
        String sha1 = hashes == null ? null : getString(hashes, "sha1");
        downloadAndVerify(url, new File(modsDir, filename), sha1);
    }

    private static JsonObject primaryFile(JsonArray files) {
        for (JsonElement element : files) {
            JsonObject file = element.getAsJsonObject();
            if (file.has("primary") && file.get("primary").getAsBoolean()) return file;
        }
        return files.get(0).getAsJsonObject();
    }

    private static void downloadAndVerify(String url, File destination, String expectedSha1) throws IOException, NoSuchAlgorithmException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "Electron-Mobile/1.0 (Modrinth mod installer)");
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Could not create mods directory");
        File temporary = new File(destination.getAbsolutePath() + ".download");
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            temporary.delete();
            throw e;
        } finally {
            connection.disconnect();
        }
        if (expectedSha1 != null && !expectedSha1.equalsIgnoreCase(toHex(digest.digest()))) {
            temporary.delete();
            throw new IOException("SHA-1 verification failed for " + destination.getName());
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IOException("Could not replace " + destination.getName());
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Could not save " + destination.getName());
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return value.toString();
    }

    private static String getString(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    @Override
    public ModLoader installMod(ModDetail modDetail, int selectedVersion) throws IOException {
        if (!modDetail.isModpack) throw new IOException("Individual mods use the profile-aware Modrinth installer");
        return ModpackInstaller.installModpack(modDetail, selectedVersion, this::installMrpack);
    }

    @Override
    public ModLoader importModpack(File modpackFile) throws IOException, NoSuchAlgorithmException {
        return ModpackInstaller.importModpack(modpackFile, Constants.SOURCE_MODRINTH, this::installMrpack);
    }

    private static ModLoader createInfo(ModrinthIndex index) {
        if (index == null || index.dependencies == null) return null;
        Map<String, String> dependencies = index.dependencies;
        String mcVersion = dependencies.get("minecraft");
        if (mcVersion == null) return null;
        String loaderVersion;
        if ((loaderVersion = dependencies.get("forge")) != null) return new ModLoader(ModLoader.MOD_LOADER_FORGE, loaderVersion, mcVersion);
        if ((loaderVersion = dependencies.get("fabric-loader")) != null) return new ModLoader(ModLoader.MOD_LOADER_FABRIC, loaderVersion, mcVersion);
        if ((loaderVersion = dependencies.get("quilt-loader")) != null) return new ModLoader(ModLoader.MOD_LOADER_QUILT, loaderVersion, mcVersion);
        if ((loaderVersion = dependencies.get("neoforge")) != null) return new ModLoader(ModLoader.MOD_LOADER_NEOFORGE, loaderVersion, mcVersion);
        return null;
    }

    private ModLoader installMrpack(File mrpackFile, File instanceDestination) throws IOException {
        try (ZipFile zip = new ZipFile(mrpackFile)) {
            ModrinthIndex index = Tools.GLOBAL_GSON.fromJson(
                    Tools.read(ZipUtils.getEntryStream(zip, "modrinth.index.json")), ModrinthIndex.class);
            ModDownloader downloader = new ModDownloader(instanceDestination);
            for (ModrinthIndex.ModrinthIndexFile file : index.files) {
                downloader.submitDownload(file.fileSize, file.path, file.hashes.sha1, file.downloads);
            }
            downloader.awaitFinish(new DownloaderProgressWrapper(R.string.modpack_download_downloading_mods, ProgressLayout.INSTALL_MODPACK));
            ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.modpack_download_applying_overrides, 1, 2);
            ZipUtils.zipExtract(zip, "overrides/", instanceDestination);
            ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 50, R.string.modpack_download_applying_overrides, 2, 2);
            ZipUtils.zipExtract(zip, "client-overrides/", instanceDestination);
            return createInfo(index);
        }
    }

    class ModrinthSearchResult extends SearchResult {
        int previousOffset;
    }
}
