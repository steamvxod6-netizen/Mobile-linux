import java.io.*;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Mobile Linux — мини-терминал: creato (создать файл и редактировать), python, exit.
 * Запуск (Java 11+):  java MobileLinux.java
 */
public class MobileLinux {
    static final Reader in = new InputStreamReader(System.in, StandardCharsets.UTF_8);
    static final PrintStream out =
            new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
    static final Object LOCK = new Object();

    // коды специальных клавиш (за пределами Unicode)
    static final int K_UP = 0x110001, K_DOWN = 0x110002, K_LEFT = 0x110003, K_RIGHT = 0x110004,
            K_HOME = 0x110005, K_END = 0x110006, K_PGUP = 0x110007, K_PGDN = 0x110008,
            K_DEL = 0x110009, K_MAX = 0x110000;

    static int rows = 24, cols = 80;
    static volatile Runnable onResize = () -> {};

    static final StringBuilder buf = new StringBuilder();
    static int pos, histIdx, curRow;
    static final List<String> history = new ArrayList<>();

    // текущая папка (в Java её нельзя сменить, поэтому храним сами)
    static Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    static final Path BASE = cwd;   // «домашняя» папка программы, показывается как ~

    static Path res(String name) {
        if (name.equals("~")) return BASE;
        if (name.startsWith("~/")) return BASE.resolve(name.substring(2)).normalize();
        return cwd.resolve(name).normalize();
    }

    static String pathLabel() {
        if (cwd.equals(BASE)) return "~";
        if (cwd.startsWith(BASE)) return "~/" + BASE.relativize(cwd).toString().replace('\\', '/');
        return cwd.toString();
    }

    static int promptLen() { return pathLabel().length() + 3; }
    static String promptColored() { return "\033[32m" + pathLabel() + "\033[0m $ "; }

    public static void main(String[] args) throws Exception {
        stty("-icanon -echo -isig -ixon min 1");
        out.print("\033[?1000l\033[?1006l");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            out.print("\033[?25h");
            out.flush();
            stty("sane");
        }));
        installResize();
        updateSize();
        onResize = MobileLinux::updateSize;
        banner();

        while (true) {
            String line = readShellLine();
            if (line == null) break;
            line = line.trim();
            if (line.isEmpty()) continue;
            history.add(line);
            String[] t = line.split("\\s+", 2);
            if (t[0].equals("exit")) break;
            else if (t[0].equals("creato")) {
                if (t.length < 2) out.println("creato: укажи имя файла, например: creato main.py");
                else creato(t[1].trim());
            } else if (t[0].equals("start")) {
                startCmd(t.length > 1 ? t[1] : "");
            } else if (t[0].equals("allow")) {
                allowCmd();
            } else if (t[0].equals("li")) {
                liCmd(t.length > 1 ? t[1] : "");
            } else if (t[0].equals("go")) {
                goCmd(t.length > 1 ? t[1] : "");
            } else if (t[0].equals("help")) {
                help(t.length > 1 ? t[1] : "");
            } else if (t[0].equals("search") || t[0].equals("list") || t[0].equals("show")
                    || t[0].equals("remove")) {
                pkgCmd(t[0], t.length > 1 ? t[1] : "");
            } else if (t[0].equals("download")) {
                downloadCmd(t.length > 1 ? t[1] : "");
            } else if (t[0].equals("python") || t[0].equals("python3")) {
                runPython(t.length > 1 ? t[1] : "");
            } else if (!extra(t[0], t.length > 1 ? t[1] : "")) {
                out.println(t[0] + ": команда не найдена. Список команд: help");
            }
        }
    }

    static void banner() {
        out.println("Welcome to Mobile Linux");
        out.println();
        out.println("Docs:       https://github.com/coreneuralsystem-ai/MobileLinux");
        out.println();
        out.println("Working with files:");
        out.println(" - Create:   creato <file>");
        out.println(" - Install:  download pkt python");
        out.println(" - Start:    start python <file.py>");
        out.println(" - Commands: help");
        out.println(" - Exit:     exit");
        out.println();
        out.println("Report issues at https://github.com/coreneuralsystem-ai/MobileLinux/issues");
        out.println();
    }

    // ---------- пакеты ----------
    static final class Pkg {
        final String name, ver, url, label, localGlob, mainClass, hint;
        final long minBytes;
        Pkg(String name, String ver, String url, String label, String localGlob, long minBytes,
            String mainClass, String hint) {
            this.name = name; this.ver = ver; this.url = url; this.label = label;
            this.localGlob = localGlob; this.minBytes = minBytes; this.mainClass = mainClass; this.hint = hint;
        }
        String fileName() { return url.substring(url.lastIndexOf('/') + 1); }
    }

    static final Pkg PY = new Pkg("python", "2.7.4",
            "https://repo1.maven.org/maven2/org/python/jython-standalone/2.7.4/jython-standalone-2.7.4.jar",
            "Jython", "jython-standalone*.jar", 5_000_000L, null,
            "Это Jython (Python 2.7 на Java): print(\"текст\") работает, f-строк нет.");
    static final Pkg JS = new Pkg("javascript", "1.7.15",
            "https://repo1.maven.org/maven2/org/mozilla/rhino/1.7.15/rhino-1.7.15.jar",
            "Rhino", "rhino-1*.jar", 500_000L, "org.mozilla.javascript.tools.shell.Main",
            "Это Rhino (JavaScript на Java): работает console.log(\"текст\").");

    /** Добавляет в Rhino обычный console.log (в самом Rhino есть только print). */
    static final String JS_PRELUDE = "var console={};"
            + "console.log=console.info=console.warn=console.error=function(){"
            + "var a=[];for(var i=0;i<arguments.length;i++)a.push(String(arguments[i]));"
            + "print(a.join(' '));};";

    static Pkg pkgByName(String n) {
        n = n.toLowerCase();
        if (n.equals("python") || n.equals("python3") || n.equals("py")) return PY;
        if (n.equals("javascript") || n.equals("js")) return JS;
        return null;
    }

    static void pause(int ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    static String mb(long bytes) { return String.format(Locale.US, "%.1f MB", bytes / 1048576.0); }

    static Path[] pkgDirs() {
        return new Path[]{
            Paths.get(System.getProperty("user.home"), ".mobilelinux", "pkg"),
            Paths.get(System.getProperty("java.io.tmpdir"), "mobilelinux-pkg")};
    }

    static Path findJar(Pkg p) {
        for (Path d : pkgDirs()) {
            Path j = d.resolve(p.fileName());
            if (Files.isRegularFile(j)) return j;
        }
        return null;
    }

    /** Папка для пакетов: домашняя, а если туда писать нельзя — временная. */
    static Path writablePkgDir() {
        for (Path d : pkgDirs()) {
            try {
                Files.createDirectories(d);
                if (Files.isWritable(d)) return d;
            } catch (IOException ignored) {}
        }
        return null;
    }

    /** Ищет файл пакета, который пользователь уже скачал сам. */
    static Path findLocalJar(Pkg p) {
        String home = System.getProperty("user.home");
        String[] ds = {cwd.toString(), home, "/sdcard/Download",
                "/storage/emulated/0/Download", "/sdcard", home + "/storage/downloads"};
        for (String d : ds) {
            if (d == null) continue;
            Path dir = Paths.get(d);
            if (!Files.isDirectory(dir)) continue;
            try (DirectoryStream<Path> st = Files.newDirectoryStream(dir, p.localGlob)) {
                for (Path x : st) if (Files.isRegularFile(x) && Files.size(x) > p.minBytes) return x;
            } catch (IOException ignored) {}
        }
        return null;
    }

    static long headSize(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            int code = c.getResponseCode();
            long len = c.getContentLengthLong();
            c.disconnect();
            return code == 200 ? len : -1;
        } catch (Exception e) { return -1; }
    }

    static void step(String text, int ms) {
        out.print(text + "...");
        out.flush();
        pause(ms);
        out.println(" Done");
    }

    /** download pkt <пакет> */
    static void downloadCmd(String args) throws IOException {
        String[] a = args.trim().split("\\s+");
        if (a.length < 2 || !a[0].equals("pkt")) {
            out.println("Использование: download pkt python   или   download pkt javascript");
            return;
        }
        Pkg p = pkgByName(a[1]);
        if (p == null) { out.println("E: пакет '" + a[1] + "' не найден. Есть: python, javascript"); return; }
        installPkg(p);
    }

    static void installPkg(Pkg p) throws IOException {
        if (findJar(p) != null) {
            out.println(p.name + " is already the newest version (" + p.ver + ").");
            return;
        }
        Path local = findLocalJar(p);
        // --- начало: как в Termux ---
        out.println("Checking availability of repository:");
        long size;
        if (local != null) {
            out.print("[*] " + local.getParent() + ": ");
            out.flush();
            size = Files.size(local);
            pause(300);
            out.println("ok");
        } else {
            out.print("[*] repo1.maven.org: ");
            out.flush();
            size = headSize(p.url);
            if (size <= 0) {
                out.println("failed");
                out.println("E: нет связи с репозиторием, и рядом нет файла " + p.localGlob + ".");
                out.println("   Проверь интернет или положи файл в папку " + cwd);
                return;
            }
            pause(300);
            out.println("ok");
        }
        step("Reading package lists", 350);
        step("Building dependency tree", 350);
        step("Reading state information", 300);
        out.println("The following NEW packages will be installed:");
        out.println("  " + p.name);
        out.println("0 upgraded, 1 newly installed, 0 to remove and 0 not upgraded.");
        out.println("Need to get " + mb(size) + " of archives.");
        out.println("After this operation, " + mb(size) + " of additional disk space will be used.");
        out.print("Do you want to continue? [Y/n] ");
        out.flush();
        while (true) {
            int k = readKey();
            if (k == -1) return;
            if (k == 'y' || k == 'Y' || k == 'н' || k == 'Н') { out.println("Y"); break; }
            if (k == 'n' || k == 'N' || k == 'т' || k == 'Т') { out.println("N"); out.println("Abort."); return; }
        }

        Path dir = writablePkgDir();
        if (dir == null) { out.println("E: некуда сохранять пакет (нет доступной для записи папки)."); return; }
        Path jar = dir.resolve(p.fileName());
        Path part = dir.resolve(p.fileName() + ".part");
        String src = local != null ? "file:" + local : "https://repo1.maven.org/maven2";
        out.println("Get:1 " + src + " stable/main all " + p.name + " all " + p.ver + " [" + mb(size) + "]");
        long t0 = System.currentTimeMillis();
        try {
            if (local != null) {
                try (InputStream is = Files.newInputStream(local)) { transfer(is, part, size); }
            } else downloadFile(p.url, part, size);
            Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try { Files.deleteIfExists(part); } catch (IOException ignored) {}
            out.println();
            out.println("E: не удалось установить: " + e.getMessage());
            return;
        }
        long ms = Math.max(1, System.currentTimeMillis() - t0);
        out.println(String.format(Locale.US, "Fetched %s in %ds (%.1f MB/s)", mb(size), ms / 1000,
                size / 1048576.0 / (ms / 1000.0)));

        // --- конец: как в Termux ---
        out.println("Selecting previously unselected package " + p.name + ".");
        for (int pct = 0; pct <= 100; pct += 20) {
            out.print("\r(Reading database ... " + pct + "%");
            out.flush();
            pause(150);
        }
        out.println();
        out.println("Preparing to unpack " + p.name + "_" + p.ver + "_all.jar ...");
        pause(300);
        out.println("Unpacking " + p.name + " (" + p.ver + ") ...");
        pause(500);
        out.println("Setting up " + p.name + " (" + p.ver + ") ...");
        pause(500);
        out.println();
        out.println("Готово. Пакет " + p.name + " установлен: " + jar);
        out.println(p.hint);
        out.println("Запуск:  start " + p.name + (p == PY ? " файл.py" : " файл.js"));
    }

    static void progress(long done, long total, long ms) {
        if (total <= 0) {
            double sp = ms > 0 ? done / 1048576.0 / (ms / 1000.0) : 0;
            out.print(String.format(Locale.US, "\r%.1f MB  %.1f MB/s ", done / 1048576.0, sp));
            out.flush();
            return;
        }
        int pct = total > 0 ? (int) (done * 100 / total) : 0;
        int barW = cols < 60 ? 8 : Math.max(8, Math.min(30, cols - 45));
        int filled = barW * pct / 100;
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < barW; i++) bar.append(i < filled ? '=' : (i == filled ? '>' : ' '));
        bar.append(']');
        double speed = ms > 0 ? done / 1048576.0 / (ms / 1000.0) : 0;
        out.print(String.format(Locale.US, "\r%3d%% %s %.1f/%.1f MB  %.1f MB/s ", pct, bar,
                done / 1048576.0, total / 1048576.0, speed));
        out.flush();
    }

    /** Копирует поток в файл, рисуя полосу прогресса. */
    static void transfer(InputStream is, Path dest, long total) throws IOException {
        long start = System.currentTimeMillis(), last = 0, done = 0;
        try (OutputStream os = Files.newOutputStream(dest)) {
            byte[] b = new byte[16384];
            int n;
            while ((n = is.read(b)) > 0) {
                os.write(b, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - last > 120) { last = now; progress(done, total, now - start); }
            }
        }
        progress(done, total, System.currentTimeMillis() - start);
        out.println();
        if (total > 0 && done != total) throw new IOException("файл получен не полностью");
    }

    static void downloadFile(String url, Path dest, long total) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        try (InputStream is = c.getInputStream()) {
            transfer(is, dest, total);
        } finally {
            c.disconnect();
        }
    }

    // ---------- пакеты: search / list / show / remove / help ----------
    static final Pkg[] ALL = {PY, JS};

    static String desc(Pkg p) {
        return p == PY ? "Python 2.7 на Java (Jython " + p.ver + ")" : "JavaScript на Java (Rhino " + p.ver + ")";
    }

    static boolean confirm() throws IOException {
        out.print("Do you want to continue? [Y/n] ");
        out.flush();
        while (true) {
            int k = readKey();
            if (k == -1) return false;
            if (k == 'y' || k == 'Y' || k == 'н' || k == 'Н') { out.println("Y"); return true; }
            if (k == 'n' || k == 'N' || k == 'т' || k == 'Т') { out.println("N"); out.println("Abort."); return false; }
        }
    }

    static void deleteTree(Path root) {
        try (java.util.stream.Stream<Path> st = Files.walk(root)) {
            for (Path x : (Iterable<Path>) st.sorted(Comparator.reverseOrder())::iterator) {
                try { Files.deleteIfExists(x); } catch (IOException ignored) {}
            }
        } catch (Exception ignored) {}
    }

    static void removePkg(Pkg p) throws IOException {
        Path jar = findJar(p);
        if (jar == null) { out.println("Package '" + p.name + "' is not installed, so not removed"); return; }
        long size = Files.size(jar);
        step("Reading package lists", 250);
        step("Building dependency tree", 250);
        step("Reading state information", 200);
        out.println("The following packages will be REMOVED:");
        out.println("  " + p.name);
        out.println("0 upgraded, 0 newly installed, 1 to remove and 0 not upgraded.");
        out.println("After this operation, " + mb(size) + " disk space will be freed.");
        if (!confirm()) return;
        out.println("Removing " + p.name + " (" + p.ver + ") ...");
        pause(500);
        for (Path d : pkgDirs()) {
            try { Files.deleteIfExists(d.resolve(p.fileName())); } catch (IOException ignored) {}
            if (p == PY) deleteTree(d.resolve("cachedir"));
        }
        out.println("Готово. Пакет " + p.name + " удалён.");
    }

    /** go <имя> — войти в папку или открыть файл; go .. — выйти на уровень выше; go — в домашнюю. */
    static void goCmd(String arg) throws IOException {
        arg = arg.trim();
        if (arg.length() >= 2 && ((arg.startsWith("\"") && arg.endsWith("\""))
                || (arg.startsWith("'") && arg.endsWith("'")))) arg = arg.substring(1, arg.length() - 1);
        if (arg.isEmpty()) { cwd = BASE; return; }
        Path target = res(arg);
        if (Files.isDirectory(target)) cwd = target;
        else if (Files.isRegularFile(target)) creato(arg);
        else out.println("go: нет такой папки или файла (или нет доступа): " + target);
    }

    /** Имя Android-приложения, в котором мы запущены (берём из пути вида /data/user/0/<пакет>/...). */
    static String androidPackage() {
        java.util.regex.Pattern pat = java.util.regex.Pattern.compile("/data/(?:user/\\d+|data)/([A-Za-z0-9_.]+)/");
        for (String d : new String[]{System.getProperty("user.home"), System.getProperty("user.dir")}) {
            if (d == null) continue;
            java.util.regex.Matcher m = pat.matcher(d + "/");
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /** allow — проверить доступ к памяти телефона и помочь его включить. */
    static void allowCmd() {
        for (String d : new String[]{"/storage/emulated/0", "/sdcard"}) {
            String[] l = new File(d).list();
            if (l != null && l.length > 0) {
                out.println("Доступ к памяти телефона уже есть: " + d);
                out.println("Войти:  go " + d);
                return;
            }
        }
        if (!new File("/storage/emulated/0").exists() && !new File("/sdcard").exists()) {
            out.println("allow: память Android здесь не видна (команда нужна только на телефоне).");
            return;
        }
        String pkg = androidPackage();
        out.println("Доступа к памяти телефона пока нет.");
        boolean opened = false;
        if (pkg != null) {
            try {
                Process pr = new ProcessBuilder("am", "start", "-a", "android.settings.APPLICATION_DETAILS_SETTINGS",
                        "-d", "package:" + pkg).redirectErrorStream(true).start();
                pr.getInputStream().readAllBytes();
                opened = pr.waitFor() == 0;
            } catch (Exception ignored) {}
        }
        out.println(opened ? "Открываю настройки приложения..." : "Автоматически открыть настройки не получилось.");
        out.println("Сделай так:");
        out.println(" 1. Настройки телефона → Приложения → " + (pkg != null ? pkg : "это приложение"));
        out.println(" 2. Разрешения → Файлы и медиа (или Память) → Разрешить.");
        out.println("    На Android 11 и новее: «Доступ ко всем файлам» → включить.");
        out.println(" 3. Вернись сюда и введи allow ещё раз.");
    }

    // ---------- команды для файлов, системы и сети ----------
    static List<String> splitArgs(String s) {
        List<String> r = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        char q = 0;
        boolean has = false;
        for (char c : s.toCharArray()) {
            if (q != 0) { if (c == q) q = 0; else b.append(c); }
            else if (c == '"' || c == '\'') { q = c; has = true; }
            else if (Character.isWhitespace(c)) {
                if (b.length() > 0 || has) { r.add(b.toString()); b.setLength(0); has = false; }
            } else b.append(c);
        }
        if (b.length() > 0 || has) r.add(b.toString());
        return r;
    }

    static String human(long n) {
        if (n < 1024) return n + " B";
        double v = n;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = -1;
        while (v >= 1024 && i < 3) { v /= 1024; i++; }
        return String.format(Locale.US, "%.1f %s", v, u[i]);
    }

    static boolean askYN(String q) throws IOException {
        out.print(q + " [Y/n] ");
        out.flush();
        while (true) {
            int k = readKey();
            if (k == -1) return false;
            if (k == 'y' || k == 'Y' || k == 'н' || k == 'Н') { out.println("Y"); return true; }
            if (k == 'n' || k == 'N' || k == 'т' || k == 'Т') { out.println("N"); return false; }
        }
    }

    /** Обрабатывает команды для файлов, системы и сети. true = команда распознана. */
    static boolean extra(String cmd, String rest) {
        try {
            List<String> a = splitArgs(rest);
            switch (cmd) {
                case "where": out.println(cwd); return true;
                case "newdir": newdirCmd(a); return true;
                case "newfile": newfileCmd(a); return true;
                case "view": viewCmd(a); return true;
                case "copy": copyCmd(a); return true;
                case "move": moveCmd(a); return true;
                case "del": delCmd(a); return true;
                case "me": out.println(System.getProperty("user.name")); return true;
                case "sysinfo": sysinfoCmd(); return true;
                case "now":
                    out.println(java.time.ZonedDateTime.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (z)")));
                    return true;
                case "disk": diskCmd(); return true;
                case "mem": memCmd(); return true;
                case "hist":
                    for (int i = 0; i < history.size(); i++) out.println(" " + (i + 1) + "  " + history.get(i));
                    return true;
                case "cls": out.print("\033[2J\033[H"); out.flush(); return true;
                case "check": checkCmd(a); return true;
                case "fetch": fetchCmd(a); return true;
                case "get": getCmd(a); return true;
                case "net": netCmd(); return true;
                case "conns": connsCmd(); return true;
                case "update": updateCmd(); return true;
                case "upgrade": upgradeCmd(); return true;
                default: return false;
            }
        } catch (IOException e) {
            out.println(cmd + ": " + e.getMessage());
            return true;
        } catch (Exception e) {
            out.println(cmd + ": ошибка: " + e);
            return true;
        }
    }

    // --- файлы и папки ---
    static void newdirCmd(List<String> a) throws IOException {
        if (a.isEmpty()) { out.println("Использование: newdir <имя>   (например: newdir проекты)"); return; }
        for (String n : a) {
            Path d = res(n);
            if (Files.exists(d)) { out.println("newdir: уже существует: " + n); continue; }
            Files.createDirectories(d);
            out.println("Создана папка: " + n);
        }
    }

    static void newfileCmd(List<String> a) throws IOException {
        if (a.isEmpty()) { out.println("Использование: newfile <имя>   (например: newfile a.txt)"); return; }
        for (String n : a) {
            Path f = res(n);
            if (Files.exists(f)) { out.println("newfile: уже существует: " + n); continue; }
            Files.createFile(f);
            out.println("Создан файл: " + n);
        }
    }

    static void viewCmd(List<String> a) throws IOException {
        if (a.isEmpty()) { out.println("Использование: view <файл>   (например: view a.txt)"); return; }
        Path f = res(a.get(0));
        if (Files.isDirectory(f)) { out.println("view: это папка. Что внутри: li"); return; }
        if (!Files.isRegularFile(f)) { out.println("view: нет такого файла: " + a.get(0)); return; }
        long size = Files.size(f);
        byte[] data;
        try (InputStream is = Files.newInputStream(f)) { data = is.readNBytes(100000); }
        for (byte b : data) if (b == 0) { out.println("view: это не текстовый файл, показать нельзя."); return; }
        String text = new String(data, StandardCharsets.UTF_8);
        out.print(text);
        if (!text.endsWith("\n")) out.println();
        if (size > data.length) out.println("... (показаны первые " + data.length + " байт из " + size + ")");
    }

    static void copyTree(Path s, Path d) throws IOException {
        try (java.util.stream.Stream<Path> st = Files.walk(s)) {
            for (Path x : (Iterable<Path>) st::iterator) {
                Path t = d.resolve(s.relativize(x).toString());
                if (Files.isDirectory(x)) Files.createDirectories(t);
                else Files.copy(x, t);
            }
        }
    }

    static void copyCmd(List<String> a) throws IOException {
        if (a.size() < 2) { out.println("Использование: copy <откуда> <куда>   (например: copy a.txt b.txt)"); return; }
        Path s = res(a.get(0)), d = res(a.get(1));
        if (!Files.exists(s)) { out.println("copy: нет такого файла или папки: " + a.get(0)); return; }
        if (Files.isDirectory(d)) d = d.resolve(s.getFileName());
        if (Files.exists(d)) { out.println("copy: уже существует: " + d.getFileName()); return; }
        if (Files.isDirectory(s)) {
            if (d.startsWith(s)) { out.println("copy: нельзя копировать папку внутрь самой себя"); return; }
            copyTree(s, d);
        } else Files.copy(s, d);
        out.println("Скопировано: " + a.get(0) + " → " + a.get(1));
    }

    static void moveCmd(List<String> a) throws IOException {
        if (a.size() < 2) { out.println("Использование: move <откуда> <куда>   (например: move a.txt b.txt)"); return; }
        Path s = res(a.get(0)), d = res(a.get(1));
        if (!Files.exists(s)) { out.println("move: нет такого файла или папки: " + a.get(0)); return; }
        if (cwd.startsWith(s)) { out.println("move: нельзя перемещать папку, в которой ты находишься"); return; }
        if (Files.isDirectory(d)) d = d.resolve(s.getFileName());
        if (Files.exists(d)) { out.println("move: уже существует: " + d.getFileName()); return; }
        Files.move(s, d);
        out.println("Перемещено: " + a.get(0) + " → " + a.get(1));
    }

    static void delCmd(List<String> a) throws IOException {
        boolean rec = false;
        List<String> names = new ArrayList<>();
        for (String x : a) { if (x.equals("-r") || x.equals("-rf")) rec = true; else names.add(x); }
        if (names.isEmpty()) {
            out.println("Использование: del <файл>   или   del -r <папка>   (удаляет папку со всем внутри)");
            return;
        }
        for (String n : names) {
            Path p = res(n);
            if (!Files.exists(p)) { out.println("del: нет такого файла или папки: " + n); continue; }
            if (p.equals(BASE) || p.getParent() == null || cwd.startsWith(p)) {
                out.println("del: нельзя удалить эту папку (ты в ней находишься или она главная): " + n);
                continue;
            }
            if (Files.isDirectory(p)) {
                if (!rec) { out.println("del: " + n + " — это папка. Чтобы удалить со всем внутри: del -r " + n); continue; }
                if (!askYN("Удалить папку '" + n + "' и всё, что внутри?")) { out.println("Отменено."); continue; }
                deleteTree(p);
                out.println("Удалена папка: " + n);
            } else {
                Files.delete(p);
                out.println("Удалён файл: " + n);
            }
        }
    }

    // --- система ---
    static String prop(String k) {
        try {
            Process p = new ProcessBuilder("getprop", k).redirectErrorStream(true).start();
            String o = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return o.isEmpty() ? null : o;
        } catch (Exception e) { return null; }
    }

    static void sysinfoCmd() {
        out.println("Система:      " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        out.println("Архитектура:  " + System.getProperty("os.arch"));
        out.println("Java:         " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        String rel = prop("ro.build.version.release"), model = prop("ro.product.model");
        if (rel != null) out.println("Android:      " + rel + (model != null ? " (" + model + ")" : ""));
        out.println("Папка программы: " + BASE);
    }

    static void diskCmd() {
        String[] ps = {cwd.toString(), "/storage/emulated/0"};
        String[] names = {"Текущая папка", "Память телефона"};
        for (int i = 0; i < ps.length; i++) {
            File f = new File(ps[i]);
            if (!f.exists() || (i == 1 && f.getTotalSpace() == 0)) continue;
            long total = f.getTotalSpace(), free = f.getUsableSpace(), used = total - free;
            out.println(names[i] + ": " + ps[i]);
            out.println("  Всего:    " + human(total));
            out.println("  Занято:   " + human(used) + (total > 0 ? " (" + (used * 100 / total) + "%)" : ""));
            out.println("  Свободно: " + human(free));
        }
    }

    static long kbLine(String l) {
        String d = l.replaceAll("[^0-9]", "");
        return d.isEmpty() ? 0 : Long.parseLong(d) * 1024;
    }

    static void memCmd() {
        try {
            long total = 0, avail = 0;
            for (String l : Files.readAllLines(Paths.get("/proc/meminfo"))) {
                if (l.startsWith("MemTotal:")) total = kbLine(l);
                if (l.startsWith("MemAvailable:")) avail = kbLine(l);
            }
            if (total > 0) {
                out.println("Память телефона:");
                out.println("  Всего:      " + human(total));
                out.println("  Занято:     " + human(total - avail));
                out.println("  Доступно:   " + human(avail));
            }
        } catch (Exception ignored) {}
        Runtime r = Runtime.getRuntime();
        out.println("Память Java:  максимум " + human(r.maxMemory()) + ", занято " + human(r.totalMemory() - r.freeMemory()));
    }

    // --- сеть ---
    static void checkCmd(List<String> a) {
        if (a.isEmpty()) { out.println("Использование: check <сайт>   (например: check google.com)"); return; }
        String h = a.get(0).replaceFirst("^[a-zA-Z]+://", "");
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        int port = 443;
        int colon = h.lastIndexOf(':');
        if (colon > 0) {
            try { port = Integer.parseInt(h.substring(colon + 1)); h = h.substring(0, colon); }
            catch (NumberFormatException ignored) {}
        }
        java.net.InetAddress addr;
        try { addr = java.net.InetAddress.getByName(h); }
        catch (Exception e) { out.println("check: не удалось найти " + h + " (нет интернета или неверное имя)"); return; }
        out.println("Проверка " + h + " [" + addr.getHostAddress() + "], порт " + port + " (TCP):");
        int ok = 0;
        for (int i = 0; i < 4; i++) {
            long t0 = System.nanoTime();
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress(addr, port), 3000);
                out.println("Ответ от " + addr.getHostAddress() + ": время=" + (System.nanoTime() - t0) / 1000000 + " мс");
                ok++;
            } catch (IOException e) {
                out.println("Нет ответа: " + e.getMessage());
            }
            pause(500);
        }
        out.println("Отправлено 4, получено " + ok + ".");
    }

    static void fetchCmd(List<String> a) throws IOException {
        if (a.isEmpty()) { out.println("Использование: fetch <адрес>   (например: fetch example.com)"); return; }
        String u = a.get(0);
        if (!u.contains("://")) u = "https://" + u;
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "MobileLinux");
        try {
            int code = c.getResponseCode();
            out.println("HTTP " + code + (c.getResponseMessage() != null ? " " + c.getResponseMessage() : ""));
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (is == null) return;
            try (InputStream in2 = is) {
                byte[] data = in2.readNBytes(20000);
                boolean more = in2.read() != -1;
                out.println(new String(data, StandardCharsets.UTF_8));
                if (more) out.println("... (показаны первые 20000 байт)");
            }
        } finally {
            c.disconnect();
        }
    }

    static void getCmd(List<String> a) throws IOException {
        if (a.isEmpty()) { out.println("Использование: get <адрес> [имя]   (например: get https://example.com/file.zip)"); return; }
        String u = a.get(0);
        if (!u.contains("://")) u = "https://" + u;
        String name;
        if (a.size() > 1) name = a.get(1);
        else {
            String path = new URL(u).getPath();
            name = path.substring(path.lastIndexOf('/') + 1);
            if (name.isEmpty()) name = "index.html";
        }
        Path dest = res(name);
        if (Files.exists(dest)) { out.println("get: файл уже существует: " + name); return; }
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "MobileLinux");
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        try {
            int code = c.getResponseCode();
            if (code >= 400) { out.println("get: ошибка сервера HTTP " + code); return; }
            long total = c.getContentLengthLong();
            out.println("Скачиваю " + u + " → " + name + (total > 0 ? " (" + human(total) + ")" : ""));
            try (InputStream is = c.getInputStream()) { transfer(is, part, total); }
            Files.move(part, dest);
            out.println("Готово: " + name + " (" + human(Files.size(dest)) + ")");
        } catch (IOException e) {
            try { Files.deleteIfExists(part); } catch (IOException ignored) {}
            throw e;
        } finally {
            c.disconnect();
        }
    }

    static void netCmd() throws Exception {
        java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces();
        if (en == null) { out.println("net: не удалось получить список сетевых интерфейсов."); return; }
        List<java.net.NetworkInterface> l = java.util.Collections.list(en);
        l.sort(Comparator.comparing(java.net.NetworkInterface::getName));
        for (java.net.NetworkInterface ni : l) {
            out.println(ni.getName() + "  " + (ni.isUp() ? "UP" : "DOWN") + (ni.isLoopback() ? " LOOPBACK" : ""));
            for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                java.net.InetAddress ad = ia.getAddress();
                String ip = ad.getHostAddress();
                int pc = ip.indexOf('%');
                if (pc > 0) ip = ip.substring(0, pc);
                out.println("  " + (ad instanceof java.net.Inet6Address ? "inet6 " : "inet ") + ip + "/" + ia.getNetworkPrefixLength());
            }
        }
    }

    static String procAddr(String h) throws Exception {
        String[] p = h.split(":");
        String hex = p[0];
        byte[] b = new byte[hex.length() / 2];
        for (int g = 0; g < hex.length() / 8; g++)
            for (int j = 0; j < 4; j++)
                b[g * 4 + j] = (byte) Integer.parseInt(hex.substring(g * 8 + (3 - j) * 2, g * 8 + (3 - j) * 2 + 2), 16);
        return java.net.InetAddress.getByAddress(b).getHostAddress() + ":" + Integer.parseInt(p[1], 16);
    }

    static String tcpState(String h) {
        switch (h.toUpperCase()) {
            case "01": return "ESTABLISHED";
            case "02": return "SYN_SENT";
            case "03": return "SYN_RECV";
            case "04": return "FIN_WAIT1";
            case "05": return "FIN_WAIT2";
            case "06": return "TIME_WAIT";
            case "07": return "CLOSE";
            case "08": return "CLOSE_WAIT";
            case "09": return "LAST_ACK";
            case "0A": return "LISTEN";
            case "0B": return "CLOSING";
            default: return h;
        }
    }

    static void connsCmd() {
        List<String> rows = new ArrayList<>();
        for (String f : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
            List<String> ls;
            try { ls = Files.readAllLines(Paths.get(f)); } catch (IOException e) { continue; }
            for (int i = 1; i < ls.size(); i++) {
                String[] p = ls.get(i).trim().split("\\s+");
                if (p.length < 4) continue;
                try {
                    rows.add(fit(f.endsWith("6") ? "tcp6" : "tcp", 6) + fit(procAddr(p[1]), 24) + fit(procAddr(p[2]), 24) + tcpState(p[3]));
                } catch (Exception ignored) {}
            }
        }
        if (rows.isEmpty()) {
            out.println("Нет данных: Android не разрешает приложениям читать список соединений (или их нет).");
            return;
        }
        out.println(fit("Тип", 6) + fit("Локальный", 24) + fit("Удалённый", 24) + "Состояние");
        for (String r : rows) out.println(r);
    }

    // --- пакеты: update / upgrade ---
    static void updateCmd() {
        step("Reading package lists", 300);
        int n = 1;
        for (Pkg p : ALL) {
            long sz = headSize(p.url);
            out.println((sz > 0 ? "Hit:" : "Err:") + n++ + " https://repo1.maven.org/maven2 " + p.name + " " + p.ver
                    + (sz > 0 ? "" : "  (нет связи)"));
        }
        out.println("Список пакетов проверен.");
    }

    static void upgradeCmd() {
        step("Reading package lists", 300);
        step("Calculating upgrade", 300);
        out.println("0 upgraded, 0 newly installed, 0 to remove.");
        out.println("Все пакеты актуальны: версии пакетов зафиксированы в этой версии программы.");
    }

    // ---------- li ----------
    /** li — что лежит в текущей папке. -a скрытые, -l подробно. */
    static void liCmd(String arg) {
        boolean all = false, lng = false;
        for (String t : arg.trim().split("\\s+")) {
            if (t.startsWith("-")) { if (t.contains("a")) all = true; if (t.contains("l")) lng = true; }
        }
        List<Path> items = new ArrayList<>();
        try (DirectoryStream<Path> st = Files.newDirectoryStream(cwd)) {
            for (Path x : st) {
                if (!all && x.getFileName().toString().startsWith(".")) continue;
                items.add(x);
            }
        } catch (IOException e) {
            out.println("li: не удалось прочитать папку: " + e.getMessage());
            return;
        }
        if (items.isEmpty()) { out.println("Внутри нет ни файлов, ни папок."); return; }
        items.sort((p1, p2) -> {
            boolean d1 = Files.isDirectory(p1), d2 = Files.isDirectory(p2);
            if (d1 != d2) return d1 ? -1 : 1;                                   // сначала папки
            return p1.getFileName().toString().compareToIgnoreCase(p2.getFileName().toString());
        });
        int w = 0;
        for (Path x : items) w = Math.max(w, x.getFileName().toString().length());
        w = Math.min(w, 30);
        java.time.format.DateTimeFormatter df =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault());
        for (Path x : items) {
            String n = x.getFileName().toString();
            boolean dir = Files.isDirectory(x);
            String kind = dir ? "папка" : "файл";
            if (!lng) { out.println(n + " ".repeat(Math.max(0, w - n.length())) + "  " + kind); continue; }
            String size = "-", date = "";
            try {
                if (!dir) size = human(Files.size(x));
                date = df.format(Files.getLastModifiedTime(x).toInstant());
            } catch (IOException ignored) {}
            out.println(fit(kind, 6) + String.format("%9s", size) + "  " + date + "  " + n);
        }
    }

    // ---------- help ----------
    static final String[][] HELP = {
        {"#", "Файлы и папки"},
        {"where", "показать, в какой папке ты сейчас", "where"},
        {"go <имя>", "войти в папку или файл; go .. — назад", "go проекты\ngo ..\ngo /sdcard"},
        {"li [-a] [-l]", "что лежит в папке (-a скрытые, -l подробно)", "li\nli -l"},
        {"newdir <имя>", "создать папку", "newdir проекты"},
        {"newfile <имя>", "создать пустой файл", "newfile a.txt"},
        {"creato <файл>", "создать файл и открыть редактор", "creato main.py\ncreato test.cpp"},
        {"view <файл>", "показать содержимое файла", "view a.txt"},
        {"copy <откуда> <куда>", "копировать файл или папку", "copy a.txt b.txt"},
        {"move <откуда> <куда>", "переместить или переименовать", "move a.txt b.txt"},
        {"del [-r] <имя>", "удалить файл (-r — папку со всем внутри)", "del a.txt\ndel -r проекты"},
        {"#", "Система"},
        {"me", "имя пользователя", "me"},
        {"sysinfo", "информация о системе", "sysinfo"},
        {"now", "дата и время", "now"},
        {"disk", "сколько места на диске", "disk"},
        {"mem", "сколько памяти (RAM)", "mem"},
        {"hist", "история введённых команд", "hist"},
        {"cls", "очистить экран", "cls"},
        {"allow", "доступ к памяти телефона", "allow"},
        {"#", "Сеть"},
        {"check <сайт>", "проверить связь с сайтом (TCP)", "check google.com"},
        {"fetch <адрес>", "показать ответ сайта", "fetch example.com"},
        {"get <адрес> [имя]", "скачать файл в текущую папку", "get https://example.com/file.zip"},
        {"net", "сетевые интерфейсы и IP-адреса", "net"},
        {"conns", "сетевые соединения", "conns"},
        {"#", "Пакеты и запуск"},
        {"download pkt <пакет>", "установить пакет (python, javascript)", "download pkt python"},
        {"update", "проверить список пакетов", "update"},
        {"upgrade", "проверить обновления пакетов", "upgrade"},
        {"search [слово]", "поиск пакетов", "search python"},
        {"list", "установленные пакеты", "list"},
        {"show <пакет>", "информация о пакете", "show python"},
        {"remove <пакет>", "удалить пакет", "remove python"},
        {"start <язык> <файл>", "найти и запустить файл", "start python a.py\nstart javascript a.js"},
        {"python <файл.py>", "запустить Python напрямую", "python a.py"},
        {"#", "Прочее"},
        {"help [команда]", "список команд; help li — подробности", "help\nhelp li"},
        {"exit", "выход", "exit"},
    };

    static void help(String arg) {
        arg = arg.trim().toLowerCase();
        if (!arg.isEmpty()) {
            for (String[] r : HELP) {
                if (r[0].equals("#")) continue;
                if (r[0].split(" ")[0].toLowerCase().equals(arg)) {
                    out.println("Команда:     " + r[0]);
                    out.println("Что делает:  " + r[1]);
                    out.println("Примеры:");
                    for (String ex : r[2].split("\n")) out.println("  " + ex);
                    return;
                }
            }
            out.println("help: нет такой команды: " + arg + ". Список: help");
            return;
        }
        out.println("Команды Mobile Linux (подробности: help <команда>)");
        for (String[] r : HELP) {
            if (r[0].equals("#")) { out.println(); out.println("== " + r[1] + " =="); continue; }
            out.println(" " + fit(r[0], 22) + " " + r[1]);
        }
        out.println();
        out.println("Редактор: ,, (две запятые) — команды: Q сохранить, X выйти, N новый файл.");
        out.println("          .. (две точки) — назад к тексту.");
    }

    /** search / list / show / remove — работа с пакетами. */
    static void pkgCmd(String sub, String rest) throws IOException {
        String[] a = rest.trim().isEmpty() ? new String[0] : rest.trim().split("\\s+");
        switch (sub) {
            case "search": {
                String q = a.length > 0 ? a[0].toLowerCase() : "";
                out.println("Sorting... Done");
                out.println("Full Text Search... Done");
                for (Pkg p : ALL) {
                    if (q.isEmpty() || p.name.contains(q) || desc(p).toLowerCase().contains(q)) {
                        out.println(p.name + "/stable " + p.ver + " all" + (findJar(p) != null ? " [installed]" : ""));
                        out.println("  " + desc(p));
                        out.println();
                    }
                }
                return;
            }
            case "list": {
                out.println("Listing... Done");
                for (Pkg p : ALL) if (findJar(p) != null) out.println(p.name + "/stable " + p.ver + " all [installed]");
                return;
            }
            case "show": {
                if (a.length < 1) { out.println("Использование: show <пакет>"); return; }
                Pkg p = pkgByName(a[0]);
                if (p == null) { out.println("E: пакет '" + a[0] + "' не найден. Есть: python, javascript"); return; }
                Path jar = findJar(p);
                out.println("Package: " + p.name);
                out.println("Version: " + p.ver);
                out.println("Installed: " + (jar != null ? "yes (" + jar + ")" : "no"));
                out.println("Source: " + p.url);
                out.println("Description: " + desc(p));
                return;
            }
            case "remove": {
                if (a.length < 1) { out.println("Использование: remove <пакет>"); return; }
                Pkg p = pkgByName(a[0]);
                if (p == null) { out.println("E: пакет '" + a[0] + "' не найден. Есть: python, javascript"); return; }
                removePkg(p);
                return;
            }
        }
    }

    // ---------- start <язык> <файл> ----------
    static Path findFile(String name) {
        try {
            Path p = Paths.get(name);
            if (p.isAbsolute() && Files.isRegularFile(p)) return p;
            Path[] roots = {cwd, Paths.get(System.getProperty("user.home"))};
            for (Path r : roots) {
                Path c = r.resolve(name).normalize();
                if (Files.isRegularFile(c)) return c.toAbsolutePath();
            }
            String base = p.getFileName().toString();
            for (Path r : roots) {
                try (java.util.stream.Stream<Path> st = Files.walk(r, 4)) {
                    Optional<Path> m = st.filter(x -> x.getFileName() != null
                            && x.getFileName().toString().equals(base) && Files.isRegularFile(x)).findFirst();
                    if (m.isPresent()) return m.get().toAbsolutePath();
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    static void startCmd(String args) {
        String[] a = args.trim().split("\\s+", 2);
        if (a.length < 2 || a[0].isEmpty() || a[1].trim().isEmpty()) {
            out.println("Использование: start python <файл.py>   или   start javascript <файл.js>");
            return;
        }
        Pkg p = pkgByName(a[0]);
        if (p == null) {
            out.println("start: язык '" + a[0] + "' пока не поддерживается. Есть: python, javascript");
            return;
        }
        startPkg(p, a[1].trim());
    }

    static void startPkg(Pkg p, String file) {
        out.println("Starting " + p.name + "...");
        pause(300);
        out.print("Checking package " + p.name + "... ");
        out.flush();
        pause(300);
        Path jar = findJar(p);
        if (jar == null) {
            out.println("not found");
            out.println("E: пакет не установлен. Введи: download pkt " + p.name);
            return;
        }
        out.println("ok (" + jar.getFileName() + ")");
        out.print("Searching for " + file + "... ");
        out.flush();
        pause(300);
        Path f = findFile(file);
        if (f == null) {
            out.println("not found");
            out.println("E: файл не найден (искал в текущей папке, домашней и подпапках).");
            return;
        }
        out.println("found");
        out.println("  " + f);

        if (p == PY) {   // Jython 2.7 требует строку-кодировку, если в файле есть русские буквы
            try {
                List<String> ls = Files.readAllLines(f, StandardCharsets.UTF_8);
                boolean nonAscii = false, coding = false;
                for (int i = 0; i < ls.size(); i++) {
                    for (char ch : ls.get(i).toCharArray()) if (ch > 127) nonAscii = true;
                    if (i < 2 && ls.get(i).contains("coding")) coding = true;
                }
                if (nonAscii && !coding) {
                    out.println("W: в файле есть не-латинские буквы. Добавь первой строкой:");
                    out.println("   # -*- coding: utf-8 -*-");
                }
            } catch (IOException ignored) {}
        }

        out.println("Running " + p.name + " " + f.getFileName() + ":");
        out.println("----------------------------------------");
        int code = -1;
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        if (!Files.isExecutable(Paths.get(javaBin))) javaBin = "java";
        List<String> cmd = new ArrayList<>(Arrays.asList(javaBin, "-Dfile.encoding=UTF-8"));
        if (p.mainClass == null) {
            cmd.add("-Dpython.cachedir=" + jar.getParent().resolve("cachedir"));
            cmd.addAll(Arrays.asList("-jar", jar.toString(), f.toString()));
        } else {
            cmd.addAll(Arrays.asList("-cp", jar.toString(), p.mainClass, "-e", JS_PRELUDE, "-f", f.toString()));
        }
        stty("icanon echo isig ixon");
        try {
            code = new ProcessBuilder(cmd).directory(f.getParent().toFile()).inheritIO().start().waitFor();
        } catch (IOException e) {
            out.println("E: не удалось запустить Java: " + e.getMessage());
        } catch (InterruptedException ignored) {
        } finally {
            stty("-icanon -echo -isig -ixon min 1");
        }
        out.println("----------------------------------------");
        out.println("[Process completed (code " + code + ")]");
    }

    /** python: сначала установленный пакет (Jython), потом системный Python. */
    static void runPython(String args) {
        List<String> a = args.trim().isEmpty() ? new ArrayList<>() : Arrays.asList(args.trim().split("\\s+"));
        Path jar = findJar(PY);
        if (jar != null) {
            String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
            if (!Files.isExecutable(Paths.get(javaBin))) javaBin = "java";
            List<String> cmd = new ArrayList<>(Arrays.asList(javaBin, "-Dfile.encoding=UTF-8", "-jar", jar.toString()));
            cmd.addAll(a);
            stty("icanon echo isig ixon");
            try {
                new ProcessBuilder(cmd).directory(cwd.toFile()).inheritIO().start().waitFor();
            } catch (IOException e) {
                out.println("python: не удалось запустить Java: " + e.getMessage());
            } catch (InterruptedException ignored) {
            } finally {
                stty("-icanon -echo -isig -ixon min 1");
            }
            return;
        }
        for (String exe : new String[]{"python3", "python"}) {
            List<String> cmd = new ArrayList<>();
            cmd.add(exe);
            cmd.addAll(a);
            stty("icanon echo isig ixon");
            try {
                new ProcessBuilder(cmd).directory(cwd.toFile()).inheritIO().start().waitFor();
                stty("-icanon -echo -isig -ixon min 1");
                return;
            } catch (IOException e) {
                stty("-icanon -echo -isig -ixon min 1");
            } catch (InterruptedException e) {
                stty("-icanon -echo -isig -ixon min 1");
                return;
            }
        }
        out.println("python: не найден. Установи пакет командой: download pkt python");
    }

    // ---------- строка команд ----------
    static void refreshLine() {
        synchronized (LOCK) {
            StringBuilder sb = new StringBuilder();
            if (curRow > 0) sb.append("\033[").append(curRow).append('A');
            sb.append("\r\033[J").append(promptColored()).append(buf);
            int endAbs = promptLen() + buf.length();
            if (endAbs % cols == 0) sb.append(" \r");            // принудительный перенос
            int endRow = endAbs / cols;
            int curAbs = promptLen() + pos;
            int row = curAbs / cols, col = curAbs % cols;
            if (row < endRow) sb.append("\033[").append(endRow - row).append('A');
            else if (row > endRow) sb.append("\033[").append(row - endRow).append('B');
            sb.append("\033[").append(col + 1).append('G');
            curRow = row;
            out.print(sb);
            out.flush();
        }
    }

    static String readShellLine() throws IOException {
        buf.setLength(0);
        pos = 0;
        histIdx = history.size();
        curRow = 0;
        refreshLine();
        while (true) {
            int k = readKey();
            if (k == -1) return null;
            if (k == 13 || k == 10) {
                int endRow = (promptLen() + buf.length()) / cols;
                if (endRow > curRow) out.print("\033[" + (endRow - curRow) + "B");
                out.print("\r\n");
                out.flush();
                return buf.toString();
            }
            else if (k == 3) { out.print("^C\r\n"); out.flush(); return ""; }
            else if (k == 4) { if (buf.length() == 0) { out.print("\r\n"); return null; } }
            else if (k == 12) { out.print("\033[2J\033[H"); curRow = 0; refreshLine(); }
            else if (k == 127 || k == 8) { if (pos > 0) { buf.deleteCharAt(pos - 1); pos--; refreshLine(); } }
            else if (k == K_DEL) { if (pos < buf.length()) { buf.deleteCharAt(pos); refreshLine(); } }
            else if (k == K_LEFT) { if (pos > 0) { pos--; refreshLine(); } }
            else if (k == K_RIGHT) { if (pos < buf.length()) { pos++; refreshLine(); } }
            else if (k == K_HOME) { pos = 0; refreshLine(); }
            else if (k == K_END) { pos = buf.length(); refreshLine(); }
            else if (k == K_UP) {
                if (histIdx > 0) { histIdx--; buf.setLength(0); buf.append(history.get(histIdx)); pos = buf.length(); refreshLine(); }
            } else if (k == K_DOWN) {
                if (histIdx < history.size()) {
                    histIdx++;
                    buf.setLength(0);
                    if (histIdx < history.size()) buf.append(history.get(histIdx));
                    pos = buf.length();
                    refreshLine();
                }
            } else if (k >= 32 && k < K_MAX) { buf.insert(pos, (char) k); pos++; refreshLine(); }
        }
    }

    // ---------- терминал ----------
    static void stty(String a) {
        try { new ProcessBuilder("sh", "-c", "stty " + a + " < /dev/tty").inheritIO().start().waitFor(); }
        catch (Exception ignored) {}
    }

    static void updateSize() {
        try {
            Process p = new ProcessBuilder("sh", "-c", "stty size < /dev/tty").redirectErrorStream(true).start();
            String s = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            String[] x = s.split("\\s+");
            rows = Integer.parseInt(x[0]);
            cols = Integer.parseInt(x[1]);
        } catch (Exception e) { rows = 24; cols = 80; }
        if (rows < 16) rows = 16;
        if (cols < 24) cols = 24;
    }

    static void installResize() {
        try {
            Class<?> sig = Class.forName("sun.misc.Signal");
            Class<?> hnd = Class.forName("sun.misc.SignalHandler");
            Object s = sig.getConstructor(String.class).newInstance("WINCH");
            Object h = Proxy.newProxyInstance(hnd.getClassLoader(), new Class<?>[]{hnd}, (p, m, a) -> {
                if (m.getName().equals("handle")) { try { onResize.run(); } catch (Throwable ignored) {} }
                return null;
            });
            sig.getMethod("handle", sig, hnd).invoke(null, s, h);
        } catch (Throwable ignored) {}
    }

    static String fit(String s, int w) {
        if (w <= 0) return "";
        if (s.length() > w) return s.substring(0, w);
        StringBuilder b = new StringBuilder(s);
        while (b.length() < w) b.append(' ');
        return b.toString();
    }

    static String center(String s, int w) {
        return fit(" ".repeat(Math.max(0, (w - s.length()) / 2)) + s, w);
    }

    // ---------- ввод ----------
    static int readKey() throws IOException {
        while (true) {
            int c = in.read();
            if (c == -1) return -1;
            if (c != 27) return c;
            if (!in.ready()) return 27;
            int d = in.read();
            if (d != '[' && d != 'O') continue;
            StringBuilder pr = new StringBuilder();
            int e;
            while ((e = in.read()) != -1) {
                if (e >= 0x40 && e <= 0x7E) break;
                pr.append((char) e);
            }
            int k = seqKey(pr.toString(), e);
            if (k != 0) return k;
        }
    }

    static int seqKey(String ps, int e) {
        String num = ps.split(";")[0];
        switch (e) {
            case 'A': return K_UP;
            case 'B': return K_DOWN;
            case 'C': return K_RIGHT;
            case 'D': return K_LEFT;
            case 'H': return K_HOME;
            case 'F': return K_END;
            case '~':
                switch (num) {
                    case "1": case "7": return K_HOME;
                    case "4": case "8": return K_END;
                    case "3": return K_DEL;
                    case "5": return K_PGUP;
                    case "6": return K_PGDN;
                }
        }
        return 0;
    }

    // ---------- creato ----------
    static List<StringBuilder> L;
    static int cx, cy, top, left;
    static boolean modified, cmdMode;
    static String status = "", lang = "Текст", curName = "";
    static StringBuilder clip;
    static Path curPath;

    static String detectLang(String name) {
        String n = name.toLowerCase();
        int i = n.lastIndexOf('.');
        String e = i >= 0 ? n.substring(i + 1) : "";
        switch (e) {
            case "py": return "Python";
            case "java": return "Java";
            case "js": return "JavaScript";
            case "ts": return "TypeScript";
            case "c": return "C";
            case "cpp": case "cc": case "cxx": return "C++";
            case "h": case "hpp": return "C/C++ header";
            case "sh": case "bash": return "Shell";
            case "go": return "Go";
            case "rs": return "Rust";
            case "php": return "PHP";
            case "lua": return "Lua";
            case "rb": return "Ruby";
            case "pl": return "Perl";
            case "kt": return "Kotlin";
            case "html": case "htm": return "HTML";
            case "css": return "CSS";
            case "json": return "JSON";
            case "md": return "Markdown";
            case "sql": return "SQL";
            case "xml": return "XML";
            case "yml": case "yaml": return "YAML";
            default: return "Текст";
        }
    }

    static void creato(String name) throws IOException {
        Path p = res(name);
        if (Files.isDirectory(p)) { out.println("creato: " + name + ": это папка"); return; }
        boolean existed = Files.exists(p);
        if (!existed) {
            try { Files.createFile(p); }
            catch (IOException e) { out.println("creato: " + name + ": " + e.getMessage()); return; }
        }
        L = new ArrayList<>();
        if (existed) for (String s : Files.readAllLines(p)) L.add(new StringBuilder(s));
        if (L.isEmpty()) L.add(new StringBuilder());
        cx = 0; cy = 0; top = 0; left = 0; modified = false; clip = null; cmdMode = false;
        curPath = p;
        curName = name;
        lang = detectLang(name);
        status = existed ? "Прочитано строк: " + L.size() : "Создан новый файл";

        updateSize();
        out.print("\033[?1049h\033[2J");
        onResize = () -> { updateSize(); synchronized (LOCK) { out.print("\033[2J"); draw(); } };
        try {
            while (true) {
                draw();
                int c = readKey();
                if (c == -1) break;
                status = "";
                if (c == 24) { if (tryExit()) break; }                 // Ctrl+X
                else if (cmdMode) { if (command(c)) break; }
                else if (c == 9 || c == 27 || c == '`') { cmdMode = true; dotPending = false; status = ">> Режим команд включён"; }
                else if (c == ',' && cx > 0 && L.get(cy).charAt(cx - 1) == ',') {
                    L.get(cy).deleteCharAt(cx - 1);                        // две запятые ,, — режим команд
                    cx--;
                    cmdMode = true;
                    dotPending = false;
                    status = ">> Режим команд включён";
                }
                else handleKey(c);
            }
        } finally {
            onResize = MobileLinux::updateSize;
            out.print("\033[?25h\033[?1049l");
            out.flush();
        }
    }

    static boolean dotPending;

    /** Режим команд: одна клавиша — одно действие. Выход из режима — две точки. true = выйти из creato. */
    static boolean command(int c) throws IOException {
        int k = c < K_MAX ? Character.toLowerCase(c) : c;
        if (k == '.') {
            if (dotPending) { dotPending = false; cmdMode = false; status = "<< Режим текста. Можно писать."; }
            else { dotPending = true; status = "Нажми точку ещё раз, чтобы выйти из команд"; }
            return false;
        }
        dotPending = false;
        if (k == 'q' || k == 'й') save();                                     // Q — сохранить
        else if (k == 'x' || k == 'ч') return tryExit();                      // X — выйти
        else if (k == 'n' || k == 'т') newFile();                             // N — новый файл
        else if (k == 9 || k == 27) { cmdMode = false; status = "<< Режим текста. Можно писать."; }
        else if (k >= K_UP && k <= K_PGDN) handleKey(c);                      // стрелки работают и тут
        else status = "Режим команд: Q сохранить, X выйти, N новый файл. Две точки .. — писать текст";
        return false;
    }

    /** Выход: если есть несохранённые правки — спрашивает. true = выйти. */
    static boolean tryExit() throws IOException {
        if (!modified) return true;
        status = "Сохранить изменения? Нажми Y — да, N — нет, другая клавиша — отмена";
        draw();
        int k = readKey();
        if (k == 'y' || k == 'Y' || k == 'н' || k == 'Н') { save(); return true; }
        if (k == 'n' || k == 'N' || k == 'т' || k == 'Т') return true;
        status = "Отменено";
        return false;
    }

    /** Новый файл прямо из creato: спрашивает имя, создаёт и открывает. */
    static void newFile() {
        try {
            String name = promptName();
            if (name == null || name.isEmpty()) { status = "Отменено"; return; }
            if (modified) {
                status = "Сохранить текущий файл? Y — да, N — нет, другая клавиша — отмена";
                draw();
                int k = readKey();
                if (k == 'y' || k == 'Y' || k == 'н' || k == 'Н') save();
                else if (k != 'n' && k != 'N' && k != 'т' && k != 'Т') { status = "Отменено"; return; }
            }
            Path np = res(name);
            if (Files.isDirectory(np)) { status = "Это папка"; return; }
            boolean existed = Files.exists(np);
            if (!existed) Files.createFile(np);
            L = new ArrayList<>();
            if (existed) for (String line : Files.readAllLines(np)) L.add(new StringBuilder(line));
            if (L.isEmpty()) L.add(new StringBuilder());
            curPath = np;
            curName = name;
            lang = detectLang(name);
            cx = 0; cy = 0; top = 0; left = 0; modified = false;
            status = existed ? "Открыт существующий: " + name : "Создан файл: " + name;
        } catch (IOException e) {
            status = "Ошибка: " + e.getMessage();
        }
    }

    static String promptName() throws IOException {
        StringBuilder nm = new StringBuilder();
        while (true) {
            status = "Имя файла: " + nm + "_  (Enter — создать, ESC — отмена)";
            draw();
            int k = readKey();
            if (k == 13 || k == 10) return nm.toString().trim();
            if (k == 27) return null;
            if (k == 127 || k == 8) { if (nm.length() > 0) nm.deleteCharAt(nm.length() - 1); }
            else if (k >= 32 && k < K_MAX) nm.append((char) k);
        }
    }

    static int panelH() { return cmdMode ? 9 : 4; }
    static int bodyRows() { return rows - 1 - panelH(); }

    static void handleKey(int c) throws IOException {
        int bodyRows = bodyRows();
        switch (c) {
            case K_UP: moveV(-1); return;
            case K_DOWN: moveV(1); return;
            case K_LEFT: moveH(-1); return;
            case K_RIGHT: moveH(1); return;
            case K_HOME: cx = 0; return;
            case K_END: cx = L.get(cy).length(); return;
            case K_PGUP: moveV(-bodyRows); return;
            case K_PGDN: moveV(bodyRows); return;
            case K_DEL: delete(); return;
            case 13: case 10: enter(); return;
            case 127: case 8: backspace(); return;
            case 15: save(); return;                        // Ctrl+O
            case 1: cx = 0; return;                         // Ctrl+A
            case 5: cx = L.get(cy).length(); return;        // Ctrl+E
            case 11:                                        // Ctrl+K — вырезать строку
                clip = L.remove(cy);
                if (L.isEmpty()) L.add(new StringBuilder());
                if (cy >= L.size()) cy = L.size() - 1;
                cx = Math.min(cx, L.get(cy).length());
                modified = true;
                return;
            case 21:                                        // Ctrl+U — вставить строку
                if (clip != null) { L.add(cy, new StringBuilder(clip)); cy++; modified = true; }
                return;
            default:
                if (c >= 32 && c < K_MAX) insert(String.valueOf((char) c));
        }
    }

    static void moveV(int n) {
        cy = Math.max(0, Math.min(L.size() - 1, cy + n));
        cx = Math.min(cx, L.get(cy).length());
    }

    static void moveH(int n) {
        if (n < 0) {
            if (cx > 0) cx--;
            else if (cy > 0) { cy--; cx = L.get(cy).length(); }
        } else {
            if (cx < L.get(cy).length()) cx++;
            else if (cy < L.size() - 1) { cy++; cx = 0; }
        }
    }

    static void insert(String s) { L.get(cy).insert(cx, s); cx += s.length(); modified = true; }

    static void enter() {
        StringBuilder line = L.get(cy);
        String before = line.substring(0, cx);
        StringBuilder indent = new StringBuilder();
        for (char ch : before.toCharArray()) { if (ch == ' ' || ch == '\t') indent.append(ch); else break; }
        if (lang.equals("Python") && before.trim().endsWith(":")) indent.append("    ");
        String rest = line.substring(cx);
        line.setLength(cx);
        L.add(cy + 1, new StringBuilder(indent + rest));
        cy++; cx = indent.length(); modified = true;
    }

    static void backspace() {
        if (cx > 0) { L.get(cy).deleteCharAt(cx - 1); cx--; modified = true; }
        else if (cy > 0) {
            int len = L.get(cy - 1).length();
            L.get(cy - 1).append(L.get(cy));
            L.remove(cy); cy--; cx = len; modified = true;
        }
    }

    static void delete() {
        if (cx < L.get(cy).length()) { L.get(cy).deleteCharAt(cx); modified = true; }
        else if (cy < L.size() - 1) { L.get(cy).append(L.get(cy + 1)); L.remove(cy + 1); modified = true; }
    }

    static void save() throws IOException {
        List<String> s = new ArrayList<>();
        for (StringBuilder b : L) s.add(b.toString());
        Files.write(curPath, s);
        modified = false;
        status = "Сохранено. Строк: " + s.size();
    }

    static void draw() {
        synchronized (LOCK) {
            int bodyRows = bodyRows();
            if (cy < top) top = cy;
            if (cy >= top + bodyRows) top = cy - bodyRows + 1;
            if (cx < left) left = cx;
            if (cx >= left + cols - 1) left = cx - cols + 2;

            StringBuilder sb = new StringBuilder("\033[?25l\033[H");
            String hl = "  Mobile Linux creato   " + curName + (modified ? "  [изменён]" : "")
                    + (cmdMode ? "  >> РЕЖИМ КОМАНД" : "");
            String hr = lang + "  ";
            int gap = Math.max(1, cols - hl.length() - hr.length());
            sb.append("\033[7m").append(fit(hl + " ".repeat(gap) + hr, cols - 1)).append("\033[0m");

            for (int i = 0; i < bodyRows; i++) {
                sb.append("\033[").append(i + 2).append(";1H\033[K");
                int idx = top + i;
                if (idx < L.size()) {
                    String s = L.get(idx).toString();
                    if (left < s.length()) sb.append(s, left, Math.min(s.length(), left + cols - 1));
                }
            }

            int w = cols - 1;
            int r = rows - panelH() + 1;                 // первая строка нижней панели
            String st = status.isEmpty()
                    ? (cmdMode ? ">> Режим команд. Q, X, N. Две точки .. — назад к тексту." : "Пишешь текст. Две запятые ,, — команды.")
                    : status;
            sb.append("\033[").append(r).append(";1H\033[K\033[1m").append(fit(st, w).trim()).append("\033[0m");
            r++;

            if (!cmdMode) {
                // одна большая подсказка в 3 строки
                String color = "\033[48;5;24;97;1m";
                String text = "Две запятые ,, = команды (Q сохранить, X выйти, N новый)";
                for (int l = 0; l < 3; l++) {
                    sb.append("\033[").append(r++).append(";1H\033[K").append(color)
                      .append(l == 1 ? center(text, w) : " ".repeat(w)).append("\033[0m");
                }
            } else {
                String[] labels = {"Q   СОХРАНИТЬ", "X   ВЫЙТИ", "N   НОВЫЙ ФАЙЛ", "ДВЕ ТОЧКИ ..   НАЗАД К ТЕКСТУ"};
                String[] colors = {"\033[48;5;28;97;1m", "\033[48;5;124;97;1m",
                                   "\033[48;5;25;97;1m", "\033[48;5;240;97;1m"};
                for (int i = 0; i < 4; i++) {
                    for (int l = 0; l < 2; l++) {
                        sb.append("\033[").append(r++).append(";1H\033[K").append(colors[i])
                          .append(l == 0 ? center(labels[i], w) : " ".repeat(w)).append("\033[0m");
                    }
                }
            }

            sb.append("\033[").append(cy - top + 2).append(';').append(cx - left + 1).append("H\033[?25h");
            out.print(sb);
            out.flush();
        }
    }
}
