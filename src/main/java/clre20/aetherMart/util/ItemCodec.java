package clre20.aetherMart.util;

import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** 物品序列化：Paper serializeAsBytes（跨版本由原版 DataFixer 升級）。 */
public final class ItemCodec {

    private static final int MAX_ITEMS = 64;
    private static final int MAX_ITEM_BYTES = 2 * 1024 * 1024;

    private ItemCodec() {
    }

    public static byte[] encode(ItemStack item) {
        return item.serializeAsBytes();
    }

    public static ItemStack decode(byte[] data) {
        return ItemStack.deserializeBytes(data);
    }

    public static String encodeBase64(ItemStack item) {
        return Base64.getEncoder().encodeToString(encode(item));
    }

    public static ItemStack decodeBase64(String s) {
        return decode(Base64.getDecoder().decode(s));
    }

    /** 物品清單（null／空氣會被略過）。 */
    public static byte[] encodeList(List<ItemStack> items) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bos)) {
            List<ItemStack> list = new ArrayList<>();
            for (ItemStack i : items) {
                if (i != null && !i.getType().isAir() && i.getAmount() > 0) {
                    list.add(i);
                }
            }
            out.writeByte(1);
            out.writeShort(list.size());
            for (ItemStack i : list) {
                byte[] b = i.serializeAsBytes();
                out.writeInt(b.length);
                out.write(b);
            }
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static List<ItemStack> decodeList(byte[] data) {
        List<ItemStack> list = new ArrayList<>();
        if (data == null || data.length == 0) {
            return list;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int version = in.readByte();
            if (version != 1) {
                throw new IOException("unknown item list version " + version);
            }
            int n = in.readUnsignedShort();
            if (n > MAX_ITEMS) {
                throw new IOException("too many items: " + n);
            }
            for (int k = 0; k < n; k++) {
                int len = in.readInt();
                if (len <= 0 || len > MAX_ITEM_BYTES) {
                    throw new IOException("bad item length " + len);
                }
                byte[] b = new byte[len];
                in.readFully(b);
                list.add(ItemStack.deserializeBytes(b));
            }
            return list;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
