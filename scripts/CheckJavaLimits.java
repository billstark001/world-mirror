import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/** Checks physical lines and Java control flow without resolving application dependencies. */
final class CheckJavaLimits {
    private static final int MAX_LINES = 500;
    private static final int MAX_DEPTH = 4;
    private final Set<String> violations = new TreeSet<>();

    public static void main(String[] args) throws IOException {
        if (args.length == 0)
            throw new IllegalArgumentException("Pass one or more Java source roots");
        var check = new CheckJavaLimits();
        for (String root : args) check.scan(Path.of(root), (file, source) -> Set.of(source));
        check.finish();
    }

    @FunctionalInterface
    interface SourceVariants {
        Collection<String> of(Path file, String source);
    }

    void scan(Path root, SourceVariants variants) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var paths = Files.walk(root);
                var fileManager = compiler.getStandardFileManager(null, null, null)) {
            for (Path file : paths.filter(Files::isRegularFile)
                            .filter(path -> path.toString().endsWith(".java"))
                            .sorted()
                            .toList()) {
                String original = Files.readString(file);
                long lines = original.lines().count();
                if (lines > MAX_LINES)
                    violations.add(file + ": " + lines + " lines (maximum " + MAX_LINES + ")");
                for (String text : variants.of(file, original)) {
                    var input = new SimpleJavaFileObject(file.toUri(), JavaFileObject.Kind.SOURCE) {
                        @Override
                        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                            return text;
                        }
                    };
                    var task = (JavacTask) compiler.getTask(null, fileManager, diagnostic -> {
                        if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                            violations.add(file + ":" + diagnostic.getLineNumber() + ": "
                                    + diagnostic.getMessage(Locale.ROOT));
                        }
                    }, null, null, Set.of(input));
                    var positions = Trees.instance(task).getSourcePositions();
                    for (var unit : task.parse()) {
                        new DepthScanner() {
                            @Override
                            void report(Tree statement, int depth) {
                                long line = unit.getLineMap().getLineNumber(
                                        positions.getStartPosition(unit, statement));
                                violations.add(file + ":" + line + ": control-flow depth " + depth
                                        + " (maximum " + MAX_DEPTH + ")");
                            }
                        }.scan(unit, 0);
                    }
                }
            }
        }
    }

    void finish() {
        if (violations.isEmpty())
            return;
        violations.forEach(System.err::println);
        System.exit(1);
    }

    private abstract static class DepthScanner extends TreeScanner<Void, Integer> {
        abstract void report(Tree statement, int depth);

        @Override
        public Void scan(Tree tree, Integer outer) {
            if (tree == null)
                return null;
            int depth = switch (tree.getKind()) {
                case IF, FOR_LOOP, ENHANCED_FOR_LOOP, WHILE_LOOP, DO_WHILE_LOOP, TRY, SWITCH,
                        SWITCH_EXPRESSION, SYNCHRONIZED ->
                    outer + 1;
                default -> outer;
            };
            if (depth > outer && depth > MAX_DEPTH)
                report(tree, depth);
            return super.scan(tree, depth);
        }

        @Override
        public Void visitIf(IfTree tree, Integer depth) {
            scan(tree.getCondition(), depth);
            scan(tree.getThenStatement(), depth);
            Tree otherwise = tree.getElseStatement();
            scan(otherwise, otherwise instanceof IfTree ? depth - 1 : depth);
            return null;
        }

        @Override
        public Void visitMethod(MethodTree tree, Integer depth) {
            return super.visitMethod(tree, 0);
        }

        @Override
        public Void visitLambdaExpression(LambdaExpressionTree tree, Integer depth) {
            return super.visitLambdaExpression(tree, 0);
        }

        @Override
        public Void visitClass(ClassTree tree, Integer depth) {
            return super.visitClass(tree, 0);
        }
    }
}
