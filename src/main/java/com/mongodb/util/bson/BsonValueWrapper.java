package com.mongodb.util.bson;

import java.util.Objects;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonType;
import org.bson.BsonValue;

public class BsonValueWrapper implements Comparable<BsonValueWrapper> {

    private final BsonValue value;

    public BsonValueWrapper(BsonValue value) {
        this.value = value;
    }

    @Override
    public int compareTo(BsonValueWrapper other) {
        if (value == null && other.value == null) {
            return 0;
        } else if (value == null) {
            return -1;
        } else if (other.value == null) {
            return 1;
        }

        if (value.getBsonType() == BsonType.MIN_KEY) {
            if (other.value.getBsonType() == BsonType.MIN_KEY) {
                return 0; // MIN_KEY compared to MIN_KEY is equal
            } else {
                return -1; // MIN_KEY is less than any other value
            }
        } else if (other.value.getBsonType() == BsonType.MIN_KEY) {
            return 1; // Any value other than MIN_KEY is greater than MIN_KEY
        }

        if (value.getBsonType() == BsonType.MAX_KEY) {
            if (other.value.getBsonType() == BsonType.MAX_KEY) {
                return 0; // MAX_KEY compared to MAX_KEY is equal
            } else {
                return 1; // MAX_KEY is greater than any other value
            }
        } else if (other.value.getBsonType() == BsonType.MAX_KEY) {
            return -1; // Any value other than MAX_KEY is less than MAX_KEY
        }

        if (value.getClass() == other.value.getClass() && value instanceof Comparable<?> && other.value instanceof Comparable<?>) {
            @SuppressWarnings("unchecked")
            Comparable<Object> comparableValue = (Comparable<Object>) value;
            @SuppressWarnings("unchecked")
            Comparable<Object> otherComparableValue = (Comparable<Object>) other.value;
            return comparableValue.compareTo(otherComparableValue);
        } else {
            return compareNonComparableValues(value, other.value);
        }
    }

    private int compareNonComparableValues(BsonValue value1, BsonValue value2) {
        if (value1.isDocument() && value2.isDocument()) {
            BsonDocument doc1 = value1.asDocument();
            BsonDocument doc2 = value2.asDocument();
            return new ComparableBsonDocument(doc1).compareTo(new ComparableBsonDocument(doc2));
        } else if (value1.isArray() && value2.isArray()) {
            return compareArrays(value1.asArray(), value2.asArray());
        } else {
            if (value1.isNumber() && value2.isNumber()) {
                return compareNumbers(value1, value2);
            } else {
                switch (value1.getBsonType()) {
                    case STRING:
                        return value1.asString().getValue().compareTo(value2.asString().getValue());
                    case BOOLEAN:
                        return Boolean.compare(value1.asBoolean().getValue(), value2.asBoolean().getValue());
                    case DATE_TIME:
                        return Long.compare(value1.asDateTime().getValue(), value2.asDateTime().getValue());
                    case OBJECT_ID:
                        return value1.asObjectId().getValue().compareTo(value2.asObjectId().getValue());
                    case DECIMAL128:
                        return value1.asDecimal128().getValue().compareTo(value2.asDecimal128().getValue());
                    case JAVASCRIPT:
                    case JAVASCRIPT_WITH_SCOPE:
                    case REGULAR_EXPRESSION:
                    case BINARY:
                    case SYMBOL:
                    case DB_POINTER:
                    case TIMESTAMP:
                        return value1.asString().getValue().compareTo(value2.asString().getValue());
                    default:
                        throw new IllegalArgumentException("Unsupported BsonType: " + value1.getBsonType());
                }
            }
        }
    }
    
    private int compareNumbers(BsonValue value1, BsonValue value2) {
        if (value1.isDouble() || value2.isDouble()) {
            double double1 = value1.isDouble() ? value1.asDouble().getValue() : value1.asNumber().doubleValue();
            double double2 = value2.isDouble() ? value2.asDouble().getValue() : value2.asNumber().doubleValue();
            return Double.compare(double1, double2);
        } else if (value1.isInt64() || value2.isInt64()) {
            long long1 = value1.isInt64() ? value1.asInt64().getValue() : value1.asNumber().longValue();
            long long2 = value2.isInt64() ? value2.asInt64().getValue() : value2.asNumber().longValue();
            return Long.compare(long1, long2);
        } else if (value1.isInt32() && value2.isInt32()) {
            int int1 = value1.asInt32().getValue();
            int int2 = value2.asInt32().getValue();
            return Integer.compare(int1, int2);
        } else {
            Number number1 = value1.asNumber().intValue();
            Number number2 = value2.asNumber().intValue();
            return number1.intValue() - number2.intValue();
        }
    }

    private int compareArrays(BsonArray array1, BsonArray array2) {
        int size1 = array1.size();
        int size2 = array2.size();

        int minSize = Math.min(size1, size2);
        for (int i = 0; i < minSize; i++) {
            BsonValue value1 = array1.get(i);
            BsonValue value2 = array2.get(i);

            int comparison = new BsonValueWrapper(value1).compareTo(new BsonValueWrapper(value2));
            if (comparison != 0) {
                return comparison;
            }
        }

        return Integer.compare(size1, size2);
    }

    public BsonValue getValue() {
        return value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        BsonValueWrapper other = (BsonValueWrapper) obj;
        return Objects.equals(value, other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}