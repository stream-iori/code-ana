package p;

import static java.lang.Integer.parseInt;

enum ShrinkRule {
    BY_ID {
        @Override
        String lookup(String value, Provider provider) {
            return provider.fetch(parseInt(value), value);
        }
    },
    BY_ROUTE {
        @Override
        String lookup(String value, Provider provider) {
            return provider.fetch(parseInt(value), value);
        }
    };

    abstract String lookup(String value, Provider provider);

    String db(String value) {
        return lookup(value, this::format);
    }

    private String format(int index, String value) {
        return value + index;
    }

    interface Provider {
        String fetch(int index, String value);
    }
}
