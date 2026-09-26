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
}
