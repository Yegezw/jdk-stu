public class Out {

    public class In {
        boolean isOwnedBy(Out out) {
            return out == Out.this;
        }
    }

    public In getIn() {
        return new In();
    }

    // 假设 out1.onws(in2)
    public boolean onws(In in) {
        return in.isOwnedBy(this);
    }
}
