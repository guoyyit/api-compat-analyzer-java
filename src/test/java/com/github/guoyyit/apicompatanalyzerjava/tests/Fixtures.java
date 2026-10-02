package com.github.guoyyit.apicompatanalyzerjava.tests;

/** Sample Java sources shared by tests. */
final class Fixtures {

    private Fixtures() {
    }

    static final String SERVICE_V1 = """
            package com.example;
            public class Service {
                public static final int LIMIT = 10;
                public int compute(int x) { return x; }
                public String name() { return "s"; }
                protected void hook() {}
                void packagePrivate() {}
                private void hidden() {}
            }
            """;

    /** compute(int) removed, name() narrowed, hook() widened, overload added. */
    static final String SERVICE_V2 = """
            package com.example;
            public class Service {
                public static final int LIMIT = 10;
                public String name() { return "s"; }
                public void hook() {}
                void packagePrivate() {}
                private void hidden() {}
                public int compute(int x, int y) { return x + y; }
            }
            """;

    static final String CALLER = """
            package com.example;
            public class Caller {
                private final Service service = new Service();
                public int run() {
                    int total = service.compute(1) + Service.LIMIT;
                    return total;
                }
            }
            """;

    static final String BASE = """
            package com.example;
            public class Base {
                public void greet() {}
                public void onlyBase() {}
            }
            """;

    static final String BASE_V2 = """
            package com.example;
            public class Base {
                public void onlyBase() {}
            }
            """;

    static final String CHILD = """
            package com.example;
            public class Child extends Base {
                public void childOnly() {}
            }
            """;

    static final String CHILD_CALLER = """
            package com.example;
            public class ChildCaller {
                public void call() {
                    Child child = new Child();
                    child.greet();
                }
            }
            """;

    static final String IGNORED_ANNOTATION = """
            package com.example;
            public class Legacy {
                @ApiCompatIgnore(reason = "planned removal")
                public void oldWay() {}
                public void kept() {}
            }
            """;

    static final String IGNORED_COMMENT = """
            package com.example;
            public class Legacy2 {
                // api-compat:ignore
                public void oldWay() {}
            }
            """;

    static final String UNRELATED = """
            package com.example.other;
            public class Unrelated {
                public void nothing() {}
            }
            """;

    // ------------------------------------------------------------------
    // New abstract method on a concrete, inheritable public type
    // ------------------------------------------------------------------

    static final String SHAPE_V1 = """
            package com.example;
            public class Shape {
                public void draw() {}
            }
            """;

    static final String SHAPE_V2_ABSTRACT = """
            package com.example;
            public class Shape {
                public void draw() {}
                public abstract int area();
            }
            """;

    static final String SHAPE_IMPL = """
            package com.example;
            public class Square extends Shape {
            }
            """;

    static final String SHAPE_USER = """
            package com.example;
            public class ShapeUser {
                public int use() {
                    Square square = new Square();
                    square.draw();
                    return square.area();
                }
            }
            """;

    // ------------------------------------------------------------------
    // New method on a public interface
    // ------------------------------------------------------------------

    static final String REPO_V1 = """
            package com.example;
            public interface Repository {
                void find();
            }
            """;

    static final String REPO_V2_ABSTRACT = """
            package com.example;
            public interface Repository {
                void find();
                void save();
            }
            """;

    static final String REPO_V2_DEFAULT = """
            package com.example;
            public interface Repository {
                void find();
                default void save() {}
            }
            """;

    static final String REPO_IMPL = """
            package com.example;
            public class JdbcRepository implements Repository {
                public void find() {}
            }
            """;

    static final String REPO_USER = """
            package com.example;
            public class RepoUser {
                public void run(Repository repository) {
                    repository.find();
                    repository.save();
                }
            }
            """;

    // ------------------------------------------------------------------
    // Direct superclass change
    // ------------------------------------------------------------------

    static final String PARENT_OLD = """
            package com.example;
            public class ParentOld {
                public void oldFeature() {}
            }
            """;

    static final String PARENT_NEW = """
            package com.example;
            public class ParentNew {
                public void newFeature() {}
            }
            """;

    static final String CHILD_OLD_PARENT = """
            package com.example;
            public class ChildType extends ParentOld {
            }
            """;

    static final String CHILD_NEW_PARENT = """
            package com.example;
            public class ChildType extends ParentNew {
            }
            """;

    static final String CHILD_USER = """
            package com.example;
            public class ChildUser {
                public void use() {
                    ChildType child = new ChildType();
                    child.oldFeature();
                }
            }
            """;

    /** A type that gains a superclass (previously extended nothing). */
    static final String ALONE = """
            package com.example;
            public class Alone {
            }
            """;

    static final String ALONE_WITH_PARENT = """
            package com.example;
            public class Alone extends ParentNew {
            }
            """;
}
