package io.roastedroot.w9s;

import com.dylibso.chicory.wasm.types.ValType;

public final class ParamUtils {

    private ParamUtils() {}

    static long parseParam(ValType type, String value) {
        var v = value.trim();
        if (ValType.I32.equals(type)) {
            if (v.startsWith("0x") || v.startsWith("0X")) {
                return Integer.parseUnsignedInt(v.substring(2), 16) & 0xFFFFFFFFL;
            }
            return Integer.parseInt(v) & 0xFFFFFFFFL;
        } else if (ValType.I64.equals(type)) {
            if (v.startsWith("0x") || v.startsWith("0X")) {
                return Long.parseUnsignedLong(v.substring(2), 16);
            }
            return Long.parseLong(v);
        } else if (ValType.F32.equals(type)) {
            return Float.floatToRawIntBits(Float.parseFloat(v)) & 0xFFFFFFFFL;
        } else if (ValType.F64.equals(type)) {
            return Double.doubleToRawLongBits(Double.parseDouble(v));
        }
        throw new IllegalArgumentException("Unsupported type: " + type);
    }

    static String formatReturnValue(ValType type, long value) {
        if (ValType.I32.equals(type)) {
            int i = (int) value;
            return i + " (0x" + Integer.toHexString(i) + ")";
        } else if (ValType.I64.equals(type)) {
            return value + " (0x" + Long.toHexString(value) + ")";
        } else if (ValType.F32.equals(type)) {
            float f = Float.intBitsToFloat((int) value);
            return String.valueOf(f);
        } else if (ValType.F64.equals(type)) {
            double d = Double.longBitsToDouble(value);
            return String.valueOf(d);
        }
        return String.valueOf(value);
    }

    static byte[] encodeTypedValue(String type, String value) {
        return switch (type) {
            case "i32" -> {
                int v = Integer.parseInt(value);
                yield new byte[] {
                    (byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)
                };
            }
            case "i64" -> {
                long v = Long.parseLong(value);
                yield new byte[] {
                    (byte) v,
                    (byte) (v >> 8),
                    (byte) (v >> 16),
                    (byte) (v >> 24),
                    (byte) (v >> 32),
                    (byte) (v >> 40),
                    (byte) (v >> 48),
                    (byte) (v >> 56)
                };
            }
            case "f32" -> {
                int bits = Float.floatToIntBits(Float.parseFloat(value));
                yield new byte[] {
                    (byte) bits, (byte) (bits >> 8), (byte) (bits >> 16), (byte) (bits >> 24)
                };
            }
            case "f64" -> {
                long bits = Double.doubleToLongBits(Double.parseDouble(value));
                yield new byte[] {
                    (byte) bits,
                    (byte) (bits >> 8),
                    (byte) (bits >> 16),
                    (byte) (bits >> 24),
                    (byte) (bits >> 32),
                    (byte) (bits >> 40),
                    (byte) (bits >> 48),
                    (byte) (bits >> 56)
                };
            }
            default -> throw new IllegalArgumentException("Unknown type: " + type);
        };
    }
}
