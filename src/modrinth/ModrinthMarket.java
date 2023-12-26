package modrinth;

import Launcher.*;
import Launcher.base.LaunchListener;
import UIL.LProc;
import UIL.Lang;
import UIL.Swing.SSwing;
import UIL.UI;
import UIL.base.IImage;
import UIL.base.LoadingImage;
import Utils.*;
import Utils.json.Json;
import Utils.json.JsonDict;
import Utils.json.JsonElement;
import Utils.json.JsonList;
import Utils.web.WebClient;
import Utils.web.WebResponse;
import Utils.web.sURL;
import minecraft.MCLaunch;
import minecraft.MCProfileScanner;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class ModrinthMarket extends Market {
    public static final LProc
            SCANNING = new LProc(Lang.get("modrinth.scanning")),
            INSTALLING = new LProc(Lang.get("modrinth.installing")),
            DOWNLOADING = new LProc(Lang.get("modrinth.downloading"));

    private static final Object l = new Object();
    private static final ListMap<String, LoadingImage> ICONS = new ListMap<>();
    final WebClient c = new WebClient();

    public final File cache;

    public final ModrinthSupport plugin;

    public final SyncVar<TaskGroupAutoProgress> g = new SyncVar<>();

    public final ListMap<String, ModrinthTeam> teams = new ListMap<>();

    final ConcurrentLinkedQueue<ModrinthContent> list = new ConcurrentLinkedQueue<>();

    private ListMap<ModrinthContent.ModrinthVersion, Task> tasks = new ListMap<>();

    final File contentsFolder;

    public ModrinthMarket(final ModrinthSupport plugin, final IImage icon, final File cacheDir) {
        super("modrinth-support.market", icon);
        this.contentsFolder = new File(plugin.getPluginData(), "contents");
        this.plugin = plugin;
        c.allowRedirect = true;
        c.headers.put("User-Agent", "FlashLauncher/modrinth.ModrinthSupport/" + plugin.getVersion() + " (mcflashlauncher@gmail.com)");
        cache = new File(cacheDir, "icons");
        if (!cache.exists())
            cache.mkdirs();
        plugin.getContext().addTaskGroup(new TaskGroupAutoProgress() {{
            g.set(this);
            addTask(new Task() {
                @Override
                public void run() {
                    try {
                        final ByteArrayOutputStream os = new ByteArrayOutputStream();
                        final WebResponse r = c.open("GET", new sURL("https://api.modrinth.com/v2/tag/category"), os, true);
                        r.auto();
                        final JsonList l = Json.parse(new InputStreamReader(new ByteArrayInputStream(os.toByteArray()), StandardCharsets.UTF_8), true).getAsList();
                        for (final JsonDict d : l.toArray(new JsonDict[0])) {
                            if (!d.getAsString("header").equals("categories")) {
                                continue;
                            }
                            addCategory(Lang.get("categories." + d.getAsString("name")));
                        }
                    } catch (final Throwable ex) {
                        ex.printStackTrace();
                    }
                }
            });
            addTask(new Task() {
                @Override
                public void run() {
                    try {
                        if (contentsFolder.exists()) {
                            final File[] mods = contentsFolder.listFiles();
                            if (mods == null)
                                return;
                            final ArrayList<JsonElement> l = new ArrayList<>();
                            for (final File f : mods)
                                try {
                                    l.add(Json.parse(new File(f, "info.json"), "UTF-8"));
                                } catch (final Exception ex) {
                                    ex.printStackTrace();
                                }
                            byElements(l);
                        } else
                            contentsFolder.mkdirs();
                    } catch (final Exception ex) {
                        ex.printStackTrace();
                    }
                }
            });
        }});

    }

    @Override
    public void checkForUpdates(final Meta... items) {
        try {
            g.get().waitFinish();
        } catch (final Exception ex) {
            ex.printStackTrace();
        }
    }

    @Override
    public Meta[] find(final String query) {
        final ArrayList<Meta> metas = new ArrayList<>();
        try {
            final ByteArrayOutputStream os = new ByteArrayOutputStream();
            final WebResponse r = c.open("GET", new sURL("https://api.modrinth.com/v2/search?facets=[[\"project_type:mod\",\"project_type:resourcepack\"]]" + (query.isEmpty() ? "" : "&query=" + query)), os, true);
            synchronized (l) {
                r.auto();
            }
            final JsonDict root = Json.parse(new InputStreamReader(new ByteArrayInputStream(os.toByteArray()), StandardCharsets.UTF_8), true).getAsDict();
            for (final JsonElement e : root.getAsList("hits")) {
                final JsonDict ed = e.getAsDict();
                metas.add(new Meta(ed.getAsString("slug"), null, ed.getAsString("author")) {
                    final LoadingImage icon;
                    final Object[] categories;
                    private final String sd, n;

                    @Override public IImage getIcon() { return icon; }
                    @Override public Object getName() { return n; }
                    @Override public Object[] getCategories() { return categories; }
                    @Override public Object getShortDescription() { return sd; }

                    @Override
                    public TaskGroup install() {
                        final TaskGroupAutoProgress l = new TaskGroupAutoProgress(1);
                        l.addTask(new Task() {
                            @Override
                            public void run() {
                                try {
                                    final ModrinthContent m = new ModrinthContent(
                                            ModrinthMarket.this,
                                            ed.getAsString("slug"),
                                            n,
                                            icon,
                                            ed.getAsString("author"),
                                            sd.toString()
                                    );
                                    m.setAuthor(null);
                                    m.scan();
                                    if (FLCore.bindMeta(m)) {
                                        plugin.mcPlugin.addContent(m);
                                        list.add(m);
                                        m.save();
                                    }
                                } catch (final Exception ex) {
                                    ex.printStackTrace();
                                }
                            }
                        });
                        return l;
                    }

                    {
                        n = ed.getAsString("title");
                        sd = ed.getAsString("description");
                        final JsonList l = ed.getAsList("display_categories");
                        categories = new Object[l.size()];
                        for (int i = 0; i < categories.length; i++)
                            categories[i] = Lang.get("categories." + l.get(i).getAsString());
                        icon = ModrinthMarket.this.getIcon(ed.getAsString("icon_url"));
                    }
                });
            }
        } catch (final Exception ex) {
            ex.printStackTrace();
        }
        return metas.toArray(new Meta[0]);
    }

    public String getURLbyIcon(final IImage icon) {
        if (icon == null)
            return null;
        synchronized (ICONS) {
            for (final Map.Entry<String, LoadingImage> e : ICONS.entrySet())
                if (e.getValue() == icon)
                    return e.getKey();
        }
        return null;
    }

    public LoadingImage getIcon(final String iconUrl) {
        if (iconUrl.isEmpty() || iconUrl.equals("null"))
            return null;
        else
            synchronized (ICONS) {
                final LoadingImage i = ICONS.get(iconUrl), icon;
                if (i == null) {
                    ICONS.put(iconUrl, icon = new LoadingImage());
                    try {
                        final sURL url = new sURL(iconUrl);
                        final File icoF =
                                url.file.startsWith("/data") ? new File(cache, url.file.substring(5)) :
                                        url.file.startsWith("/") ? new File(cache, url.file.substring(1)) :
                                                new File(cache, url.file);
                        if (icoF.exists())
                            icon.setImage(UI.image(icoF));
                        else
                            new Thread(() -> {
                                try {
                                    final ByteArrayOutputStream os = new ByteArrayOutputStream();
                                    c.open("GET", new sURL(iconUrl), os, true).auto();
                                    final BufferedImage r = new BufferedImage(Meta.ICON_SIZE, Meta.ICON_SIZE, BufferedImage.TYPE_INT_ARGB);

                                    final Graphics2D g = (Graphics2D) r.getGraphics();
                                    g.setRenderingHints(SSwing.RH);
                                    g.drawImage(ImageIO.read(new ByteArrayInputStream(os.toByteArray())), 0, 0, r.getWidth(), r.getHeight(), null);
                                    g.dispose();

                                    icoF.getParentFile().mkdirs();
                                    ImageIO.write(r, "png", icoF);
                                    icon.setImage(UI.image(icoF));
                                } catch (final Exception ex) {
                                    ex.printStackTrace();
                                }
                            }).start();
                    } catch (final Exception ex) {
                        ex.printStackTrace();
                    }
                    return icon;
                } else
                    return i;
            }
    }

    final ModrinthContent getByID(final String id) {
        for (final ModrinthContent c : list)
            if (c.getID().equals(id))
                return c;
        return null;
    }

    final Map<JsonElement, ModrinthContent> byElements(final List<JsonElement> elements) {
        final ListMap<JsonElement, ModrinthContent> contents = new ListMap<>();
        final ArrayList<ModrinthContent> scan = new ArrayList<>();
        if (elements.isEmpty())
            return contents;
        for (final JsonElement e : elements) {
            ModrinthContent c = getByID(e.getAsDict().getAsString("slug"));
            if (c == null)
                scan.add(c = new ModrinthContent(ModrinthMarket.this, e.getAsDict().getAsString("slug")));
            contents.put(e, c);
        }
        if (!scan.isEmpty())
            try {
                StringBuilder b = new StringBuilder("https://api.modrinth.com/v2/projects?ids=[");
                for (final ModrinthContent c : scan)
                    b.append('"').append(c.getID()).append("\", ");
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                WebResponse r = c.open("GET", new sURL(b.substring(0, b.length() - 2) + ']'), os, true);
                r.auto();
                if (r.getResponseCode() != 200)
                    throw new Exception("[Modrinth] Unknown code: " + r.getResponseCode());
                final ListMap<String, ModrinthTeam> teams = new ListMap<>();
                final ListMap<String, ModrinthContent.ModrinthVersion> versions = new ListMap<>();
                for (final JsonElement el : Json.parse(os, StandardCharsets.UTF_8, true).getAsList()) {
                    final JsonDict d = el.getAsDict();
                    final String id = d.getAsString("slug");
                    for (final ModrinthContent c : scan)
                        if (id.equals(c.getID())) {
                            System.out.println("[Modrinth] Add " + id);
                            c.setName(d.getAsString("title"));
                            c.lowerName = c.getName().toString().toLowerCase();
                            c.setIcon(getIcon(d.getAsString("icon_url")));
                            c.setShortDescription(d.getAsString("description"));
                            synchronized (this.teams) {
                                c.team.set(this.teams.get(d.getAsString("team")));
                                if (c.team.get() == null) {
                                    final ModrinthTeam t = new ModrinthTeam();
                                    this.teams.put(d.getAsString("team"), t);
                                    c.team.set(t);
                                    teams.put(d.getAsString("team"), t);
                                }
                            }
                            for (final JsonElement i : Core.asReversed(d.getAsList("versions")))
                                try {
                                    final String v, vn;
                                    if (i.isDict()) {
                                        final JsonDict di = i.getAsDict();
                                        v = di.getAsString("id");
                                        vn = di.getAsString("version_number");
                                    } else {
                                        v = i.getAsString();
                                        vn = "?";
                                    }
                                    final File f = new File(contentsFolder, id + "/" + v + ".json");
                                    if (f.exists())
                                        c.versions.add(new ModrinthContent.ModrinthVersion(c, Json.parse(f, "UTF-8").getAsDict()));
                                    else {
                                        final ModrinthContent.ModrinthVersion ver = new ModrinthContent.ModrinthVersion(c, v, vn);
                                        c.versions.add(ver);
                                        versions.put(v, ver);
                                    }
                                } catch (final Exception ex) {
                                    ex.printStackTrace();
                                }
                            break;
                        }
                }
                if (!teams.isEmpty()) {
                    b = new StringBuilder("https://api.modrinth.com/v2/teams?ids=[");
                    for (final String id : teams.keySet())
                        b.append('"').append(id).append("\", ");
                    os = new ByteArrayOutputStream();
                    r = c.open("GET", new sURL(b.substring(0, b.length() - 2) + ']'), os, true);
                    r.auto();
                    if (r.getResponseCode() != 200)
                        throw new Exception("[Modrinth] Unknown code: " + r.getResponseCode());
                    for (final JsonElement e : Json.parse(os, StandardCharsets.UTF_8, true).getAsList())
                        for (final JsonElement el : e.getAsList()) {
                            final JsonDict d = el.getAsDict();
                            teams.get(d.getAsString("team_id")).add(d.getAsDict("user").getAsString("username"));
                        }
                    for (final ModrinthTeam t : teams.values())
                        t.update();
                }
                for (final ModrinthContent c : scan) {
                    c.setAuthor(c.team.toString());
                    c.save();
                }
                while (!versions.isEmpty()) {
                    b = new StringBuilder("https://api.modrinth.com/v2/versions?ids=[");
                    int i = 0;
                    final ArrayList<String> l = new ArrayList<>();
                    for (final String ver : versions.keySet()) {
                        l.add(ver);
                        b.append('"').append(ver).append("\", ");
                        if (++i == 1361)
                            break;
                    }
                    os = new ByteArrayOutputStream();
                    r = c.open("GET", new sURL(b.substring(0, b.length() - 2) + "]"), os, true);
                    r.auto();
                    if (r.getResponseCode() != 200)
                        throw new Exception("[Modrinth] Unknown code: " + r.getResponseCode());
                    for (final JsonElement e : Json.parse(os, StandardCharsets.UTF_8, true).getAsList()) {
                        final JsonDict d = e.getAsDict();
                        final String v = d.getAsString("id");
                        final ModrinthContent.ModrinthVersion ver = versions.get(v);
                        final File f = new File(contentsFolder, ver.content.getID() + "/" + v + ".json"), p = f.getParentFile();
                        if (!p.exists())
                            p.mkdirs();
                        Files.write(f.toPath(), d.toString().getBytes(StandardCharsets.UTF_8));
                        ver.from(d);
                        versions.remove(v);
                        l.remove(v);
                    }
                    if (i == 1361)
                        for (final String item : l)
                            versions.remove(item);
                    else
                        break;
                }
                for (final ModrinthContent c : scan)
                    if (FLCore.bindMeta(c)) {
                        plugin.mcPlugin.addContent(c);
                        list.add(c);
                    } else
                        contents.remove(c);
            } catch (final Exception ex) {
                ex.printStackTrace();
                for (final ModrinthContent c : scan)
                    contents.remove(Core.getKeyByValue(contents, c));
            }
        return contents;
    }

    final Runnable1a<MCProfileScanner> onScan = event -> {
        try {
            final File f = new File(event.home, "modrinth.list.json");
            if (!f.exists())
                return;
            for (final Map.Entry<JsonElement, ModrinthContent> c : byElements(Json.parse(f, "UTF-8").getAsList()).entrySet())
                try {
                    final ModrinthContent.ModrinthVersion v = c.getValue().getByID(c.getKey().getAsDict().getAsString("version"));
                    if (v != null)
                        event.contents.add(v);
                } catch (final Exception ex) {
                    ex.printStackTrace();
                }
        } catch (final Exception ex) {
            ex.printStackTrace();
        }
    };

    final RRunnable1a<LaunchListener, MCLaunch> onPreLaunch = evt -> new LaunchListener() {
        @Override
        public void preLaunch() {
            try {
                final TaskGroupAutoProgress g = new TaskGroupAutoProgress(2);
                final File
                        home = evt.configuration.workDir,
                        mods = new File(home, "mods"),
                        resourcepacks = new File(home, "resourcepacks"),
                        f = new File(home, "modrinth.list.json");
                if (!f.exists())
                    return;
                if (!mods.exists())
                    mods.mkdirs();
                if (!resourcepacks.exists())
                    resourcepacks.mkdirs();
                final JsonList l = Json.parse(f, "UTF-8").getAsList();
                final AtomicInteger index = new AtomicInteger(0);
                for (final Map.Entry<JsonElement, ModrinthContent> e : byElements(l).entrySet())
                    g.addTask(new Task() {
                        @Override
                        public void run() throws Throwable {
                            try {
                                final JsonDict d = e.getKey().getAsDict();
                                final ModrinthContent c = e.getValue();
                                final ModrinthContent.ModrinthVersion v = c.getByID(d.getAsString("version"));
                                if (v == null)
                                    return;
                                boolean cont = false;
                                for (final ModrinthContent.ModrinthVersion.ModrinthFile sf : v.files) {
                                    if (sf.filename.startsWith("/") || sf.filename.contains("..")) {
                                        System.out.println("[Modrinth] Skip " + sf.filename);
                                        continue;
                                    }
                                    if (new File(mods, sf.filename).exists())
                                        continue;
                                    cont = true;
                                    break;
                                }
                                if (!cont)
                                    return;
                                g.addTask(new Task() {
                                    final Task w;

                                    @Override
                                    public void run() throws Throwable {
                                        w.waitFinish();
                                        try {
                                            final File folder = v.isFabric || v.isQuilt || v.isForge || v.isNeoForge ? mods : v.isVanilla ? resourcepacks : null;
                                            if (folder == null)
                                                return;
                                            for (final ModrinthContent.ModrinthVersion.ModrinthFile f : v.files) {
                                                if (f.filename.startsWith("/") || f.filename.contains("..")) {
                                                    System.out.println("[Modrinth] Skip " + f.filename);
                                                    continue;
                                                }
                                                final File f2 = new File(contentsFolder, v.getContent().getID() + "/" + v.id + "/" + f.filename), file = new File(folder, f.filename);
                                                if (file.exists() || !f2.exists())
                                                    continue;
                                                Files.copy(f2.toPath(), file.toPath());
                                            }
                                        } catch (final Exception ex) {
                                            ex.printStackTrace();
                                        }
                                    }

                                    @Override
                                    public String toString() {
                                        return INSTALLING.toString(c.getName(), g.getProgress(), g.getMaxProgress());
                                    }

                                    {
                                        synchronized (tasks) {
                                            Task t = tasks.get(v);
                                            if (t == null) {
                                                t = new Task() {
                                                    @Override
                                                    public void run() {
                                                        try {
                                                            final File p = new File(contentsFolder, c.getID() + "/" + v.id);
                                                            if (!p.exists())
                                                                p.mkdirs();
                                                            for (final ModrinthContent.ModrinthVersion.ModrinthFile f : v.files) {
                                                                if (f.filename.startsWith("/") || f.filename.contains("..")) {
                                                                    System.out.println("[Modrinth] Skip " + f.filename);
                                                                    continue;
                                                                }
                                                                final File file = new File(p, f.filename).getCanonicalFile().getAbsoluteFile();
                                                                while (true)
                                                                    try {
                                                                        final ByteArrayOutputStream os = new ByteArrayOutputStream();
                                                                        final WebResponse r = ModrinthMarket.this.c.open("GET", f.url, os, true);
                                                                        r.auto();
                                                                        if (r.getResponseCode() != 200) {
                                                                            System.out.println("Unknown code: " + r.getResponseCode());
                                                                            break;
                                                                        }
                                                                        if (Core.hashToHex("SHA-512", os.toByteArray()).equals(f.sha512)) {
                                                                            Files.write(file.toPath(), os.toByteArray());
                                                                            break;
                                                                        }
                                                                    } catch (final Exception ex) {
                                                                        ex.printStackTrace();
                                                                        break;
                                                                    }
                                                            }
                                                        } catch (final Exception ex) {
                                                            ex.printStackTrace();
                                                        }
                                                    }

                                                    @Override
                                                    public String toString() {
                                                        return DOWNLOADING.toString(c.getName(), g.getProgress(), g.getMaxProgress());
                                                    }
                                                };
                                                tasks.put(v, t);
                                            }
                                            g.addTask(w = t);
                                        }
                                    }
                                });
                            } catch (final Exception ex) {
                                ex.printStackTrace();
                            } finally {
                                index.addAndGet(1);
                            }
                        }
                    });
                if (g.getTaskCount() > 0)
                    evt.configuration.addTaskGroup(g);
            } catch (final Exception ex) {
                ex.printStackTrace();
            }
        }
    };
}