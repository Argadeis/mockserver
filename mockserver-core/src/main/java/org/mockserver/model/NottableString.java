package org.mockserver.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * @author jamesdbloom
 */
public class NottableString extends Not {

    private final String value;

    private NottableString(String value, Boolean not) {
        this.value = value;
        this.not = not;
    }

    public static NottableString string(String value, Boolean not) {
        return new NottableString(value, not);
    }

    public static NottableString string(String value) {
        return new NottableString(value, null);
    }

    public static NottableString not(String value) {
        return new NottableString(value, Boolean.TRUE);
    }

    public static List<NottableString> strings(String... values) {
        return strings(Arrays.asList(values));
    }

    public static List<NottableString> strings(Collection<String> values) {
        List<NottableString> nottableValues = new ArrayList<NottableString>();
        for (String value : values) {
            nottableValues.add(string(value));
        }
        return nottableValues;
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (other == null) {
            return value != null;
        } else if (value == null) {
            return false;
        }
        if (other instanceof String) {
            return isNot() != (value.equals(other));
        } else if (other instanceof NottableString) {
            NottableString otherNottableString = (NottableString) other;
            return otherNottableString.isNot() == (isNot() == (value.equals(otherNottableString.getValue())));
        }
        return false;
    }
}
