package io.github.xmy953538104.mybox.engine.accounts;

/** Does not expose a toString representation containing a user's account name. */
public final class AccountKey {
    public final String name;
    public final String type;

    public AccountKey(String name, String type) {
        if (name == null || name.isEmpty() || type == null || type.isEmpty()) {
            throw new IllegalArgumentException("Account name and type are required");
        }
        this.name = name;
        this.type = type;
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof AccountKey)) return false;
        AccountKey key = (AccountKey) other;
        return name.equals(key.name) && type.equals(key.type);
    }

    @Override public int hashCode() { return 31 * name.hashCode() + type.hashCode(); }
}
