import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;

/**
 * Emite posiciones y contratos declarados en Java sin resolver dependencias ni ejecutar código.
 * El JSON por archivo permite comprobar documentación y rutas contra el AST real de javac.
 * @since 0.2.0-SNAPSHOT
 * @version 0.2.0-SNAPSHOT
 * @category Operaciones
 */
public class JavaSourceIndex {
    /** Serializa valores escalares, mapas y colecciones del índice como JSON válido. */
    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) return "{" + String.join(",", map.entrySet().stream()
                .map(e -> json(e.getKey()) + ":" + json(e.getValue())).toList()) + "}";
        if (value instanceof Collection<?> list) return "[" + String.join(",",
                list.stream().map(JavaSourceIndex::json).toList()) + "]";
        return "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    /** Analiza los archivos listados en UTF-8, uno por línea, y escribe un objeto por archivo. */
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, null)) {
            var names = Files.readAllLines(Path.of(args[0]));
            JavacTask task = (JavacTask) compiler.getTask(null, manager, null,
                    List.of("-proc:none", "-encoding", "UTF-8"), null,
                    manager.getJavaFileObjectsFromStrings(names));
            DocTrees trees = DocTrees.instance(task);
            for (CompilationUnitTree unit : task.parse()) {
                List<Map<String, Object>> symbols = new ArrayList<>();
                SourcePositions positions = trees.getSourcePositions();
                new TreePathScanner<Void, String>() {
                    /** Registra nombre, límites, cabecera y anotaciones de una declaración. */
                    Map<String, Object> symbol(Tree tree, String name, String owner,
                                               ModifiersTree modifiers) {
                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put("name", name);
                        data.put("owner", owner);
                        data.put("kind", tree.getKind().name());
                        data.put("start", positions.getStartPosition(unit, tree));
                        data.put("end", positions.getEndPosition(unit, tree));
                        data.put("annotations", modifiers.getAnnotations().stream()
                                .map(Object::toString).toList());
                        var doc = trees.getDocCommentTree(getCurrentPath());
                        if (doc != null) {
                            data.put("docStart", trees.getSourcePositions().getStartPosition(unit, doc, doc));
                            data.put("docEnd", trees.getSourcePositions().getEndPosition(unit, doc, doc));
                            data.put("doc", doc.toString());
                        }
                        symbols.add(data);
                        return data;
                    }
                    /** Indexa tipos, incluidos records y tipos anidados, con su propietario. */
                    @Override public Void visitClass(ClassTree tree, String owner) {
                        String name = tree.getSimpleName().toString();
                        var data = symbol(tree, name, owner, tree.getModifiers());
                        data.put("interfaces", tree.getImplementsClause().stream()
                                .map(Object::toString).toList());
                        return super.visitClass(tree, owner == null ? name : owner + "." + name);
                    }
                    /** Indexa firmas y cuerpo para detectar cambios y comentarios desactualizados. */
                    @Override public Void visitMethod(MethodTree tree, String owner) {
                        var data = symbol(tree, tree.getName().toString(), owner, tree.getModifiers());
                        data.put("returns", tree.getReturnType() == null ? null : tree.getReturnType().toString());
                        data.put("parameters", tree.getParameters().stream().map(p -> Map.of(
                                "name", p.getName().toString(), "type", p.getType().toString(),
                                "annotations", p.getModifiers().getAnnotations().stream()
                                        .map(Object::toString).toList())).toList());
                        data.put("throws", tree.getThrows().stream().map(Object::toString).toList());
                        if (tree.getBody() != null) {
                            data.put("bodyStart", positions.getStartPosition(unit, tree.getBody()));
                            data.put("bodyEnd", positions.getEndPosition(unit, tree.getBody()));
                        }
                        return super.visitMethod(tree, owner);
                    }
                    /** Indexa campos declarados y componentes de records; excluye variables locales. */
                    @Override public Void visitVariable(VariableTree tree, String owner) {
                        Tree parent = getCurrentPath().getParentPath().getLeaf();
                        if (parent instanceof ClassTree) {
                            var data = symbol(tree, tree.getName().toString(), owner, tree.getModifiers());
                            data.put("type", tree.getType() == null ? null : tree.getType().toString());
                            data.put("recordComponent", parent.getKind() == Tree.Kind.RECORD
                                    && !tree.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.STATIC));
                        }
                        return super.visitVariable(tree, owner);
                    }
                }.scan(unit, null);
                System.out.println(json(Map.of("file", Path.of(unit.getSourceFile().toUri()).toString(),
                        "package", unit.getPackageName() == null ? "" : unit.getPackageName().toString(),
                        "symbols", symbols)));
            }
        }
    }
}
