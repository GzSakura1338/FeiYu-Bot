package dafeiyu.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class CheckSupport {

    public record Result(String id, String title, boolean passed, String detail) {}

    private final List<Result> results = new ArrayList<>();
    private final String section;

    public CheckSupport(String section) {
        this.section = section;
    }

    public void check(String id, String title, boolean passed, String detail) {
        results.add(new Result(id, title, passed, detail));
        System.out.println((passed ? "PASS " : "FAIL ") + id + "  " + title + "  ->  " + detail);
    }

    public void checkThat(String id, String title, BooleanSupplier test, String detail) {
        boolean passed;
        try {
            passed = test.getAsBoolean();
        } catch (RuntimeException e) {
            passed = false;
            detail = detail + " (异常：" + e + ")";
        }
        check(id, title, passed, detail);
    }

    public static boolean waitFor(BooleanSupplier condition, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            try {
                if (condition.getAsBoolean()) {
                    return true;
                }
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (RuntimeException ignored) {
            }
        }
        try {
            return condition.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public int failed() {
        return (int) results.stream().filter(r -> !r.passed()).count();
    }

    public List<Result> results() {
        return results;
    }

    public void summary() {
        long passed = results.stream().filter(Result::passed).count();
        System.out.println();
        System.out.println("== " + section + "：" + passed + " / " + results.size() + " 通过");
        results.stream().filter(r -> !r.passed()).forEach(r -> System.out.println("   未通过：" + r.id() + " " + r.title() + " -> " + r.detail()));
    }
}
