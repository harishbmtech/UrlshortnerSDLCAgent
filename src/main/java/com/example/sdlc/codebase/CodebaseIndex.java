package com.example.sdlc.codebase;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Lightweight static index of the existing codebase: layers, declared types, public API, HTTP endpoints,
 * JPA entities and released DB migrations. It is the "ground truth" used to reason about brownfield changes
 * and to reject hallucinated file references from an LLM.
 */
public class CodebaseIndex {

    private static final Pattern TYPE_DECL = Pattern.compile("\\b(class|interface|record|enum)\\s+([A-Z]\\w*)");
    private static final Pattern PUBLIC_METHOD = Pattern.compile("public\\s+(?:static\\s+)?[\\w<>\\[\\],.? ]+\\s+(\\w+)\\s*\\(");
    private static final Pattern MAPPING = Pattern.compile("@(Get|Post|Put|Delete|Patch|Request)Mapping\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"");
    private static final Pattern BARE_MAPPING = Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping(?!\\()");

    private final List<SourceFile> files;
    private final Set<String> migrations;

    private CodebaseIndex(List<SourceFile> files, Set<String> migrations) {
        this.files = List.copyOf(files);
        this.migrations = Set.copyOf(migrations);
    }

    public static CodebaseIndex empty() {
        return new CodebaseIndex(List.of(), Set.of());
    }

    public static CodebaseIndex scan(Path sourceRoot, Path migrationsRoot) {
        List<SourceFile> files = new ArrayList<>();
        if (sourceRoot != null && Files.isDirectory(sourceRoot)) {
            try (Stream<Path> walk = Files.walk(sourceRoot)) {
                for (Path p : walk.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                    files.add(parse(sourceRoot, p));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        Set<String> migrations = new LinkedHashSet<>();
        if (migrationsRoot != null && Files.isDirectory(migrationsRoot)) {
            try (Stream<Path> list = Files.list(migrationsRoot)) {
                list.filter(f -> f.toString().endsWith(".sql")).sorted()
                        .forEach(f -> migrations.add(normalize(f)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new CodebaseIndex(files, migrations);
    }

    private static SourceFile parse(Path root, Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String code = JavaSource.stripCommentsAndLiterals(content);
        String rel = root.relativize(file).toString().replace('\\', '/');
        String layer = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : "root";

        List<String> declared = new ArrayList<>();
        Matcher td = TYPE_DECL.matcher(code);
        String kind = "class";
        while (td.find()) {
            if (declared.isEmpty()) kind = td.group(1);
            declared.add(td.group(2));
        }
        String typeName = file.getFileName().toString().replace(".java", "");

        List<String> methods = new ArrayList<>();
        Matcher pm = PUBLIC_METHOD.matcher(content);
        while (pm.find()) {
            if (!methods.contains(pm.group(1)) && !declared.contains(pm.group(1))) methods.add(pm.group(1));
        }

        List<String> endpoints = new ArrayList<>();
        String classPrefix = "";
        Matcher m = MAPPING.matcher(content);
        while (m.find()) {
            if (m.group(1).equals("Request")) {
                classPrefix = m.group(2);
            } else {
                endpoints.add(m.group(1).toUpperCase() + " " + classPrefix + m.group(2));
            }
        }
        Matcher bare = BARE_MAPPING.matcher(content);
        while (bare.find()) {
            endpoints.add(bare.group(1).toUpperCase() + " " + classPrefix);
        }
        boolean entity = content.contains("@Entity");
        return new SourceFile(normalize(file), layer, typeName, kind, declared, methods, endpoints, entity, content, code);
    }

    static String normalize(Path p) {
        Path cwd = Path.of("").toAbsolutePath();
        Path abs = p.toAbsolutePath().normalize();
        Path rel = abs.startsWith(cwd) ? cwd.relativize(abs) : p.normalize();
        return rel.toString().replace('\\', '/');
    }

    public List<SourceFile> files() {
        return files;
    }

    public Set<String> paths() {
        return files.stream().map(SourceFile::path).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public Set<String> migrations() {
        return migrations;
    }

    public boolean isEmpty() {
        return files.isEmpty();
    }

    public Optional<SourceFile> declaring(String type) {
        return files.stream().filter(f -> f.declares(type)).findFirst();
    }

    public Optional<SourceFile> byPath(String path) {
        return files.stream().filter(f -> f.path().equals(path)).findFirst();
    }

    public List<SourceFile> referencing(String symbol) {
        return files.stream().filter(f -> !f.declares(symbol) && f.references(symbol)).toList();
    }

    public List<String> endpoints() {
        return files.stream().flatMap(f -> f.endpoints().stream()).sorted().distinct().toList();
    }

    public List<String> entities() {
        return files.stream().filter(SourceFile::jpaEntity).map(SourceFile::typeName).toList();
    }

    public int nextMigrationVersion() {
        return migrations.stream()
                .map(p -> p.substring(p.lastIndexOf('/') + 1))
                .filter(n -> n.matches("V\\d+__.*"))
                .map(n -> Integer.parseInt(n.substring(1, n.indexOf("__"))))
                .max(Comparator.naturalOrder()).orElse(0) + 1;
    }

    /** Compact textual outline (for LLM prompts and reports). */
    public String outline() {
        StringBuilder sb = new StringBuilder();
        for (SourceFile f : files) {
            sb.append("- ").append(f.path()).append(" [").append(f.layer()).append("] ")
                    .append(f.kind()).append(' ').append(f.typeName());
            if (f.jpaEntity()) sb.append(" @Entity");
            if (!f.endpoints().isEmpty()) sb.append(" endpoints=").append(f.endpoints());
            if (!f.publicMethods().isEmpty()) sb.append(" methods=").append(f.publicMethods());
            sb.append('\n');
        }
        migrations.forEach(m -> sb.append("- ").append(m).append(" [migration, released]\n"));
        return sb.toString();
    }
}
