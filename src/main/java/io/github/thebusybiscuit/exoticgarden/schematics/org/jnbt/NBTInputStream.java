package io.github.thebusybiscuit.exoticgarden.schematics.org.jnbt;

/*
 * JNBT License
 *
 * Copyright (c) 2010 Graham Edgecombe
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *     * Redistributions of source code must retain the above copyright notice,
 *       this list of conditions and the following disclaimer.
 *
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *
 *     * Neither the name of the JNBT team nor the names of its
 *       contributors may be used to endorse or promote products derived from
 *       this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY
 * WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 */

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * <p>This class reads <strong>NBT</strong>, or
 * <strong>Named Binary Tag</strong> streams, and produces an object graph of
 * subclasses of the <code>Tag</code> object.</p>
 *
 * <p>The NBT format was created by Markus Persson, and the specification may
 * be found at <a href="http://www.minecraft.net/docs/NBT.txt">
 * http://www.minecraft.net/docs/NBT.txt</a>.</p>
 *
 * <p><b>安全加固</b>：schematic 文件可能被人为替换/损坏（不可信任的输入）。
 * 原实现对 {@code readInt()/readShort()} 返回的长度直接分配数组并对嵌套递归无深度限制，
 * 恶意/损坏文件可触发 {@link OutOfMemoryError}（巨型数组）或 {@link StackOverflowError}
 * （超深嵌套）导致服务器崩溃。现对数组/列表长度与递归深度加上限，越界即抛
 * {@link IOException}（可被上层 catch 兜底，转为“加载失败”而非崩服）。</p>
 *
 * @author Graham Edgecombe
 */
public final class NBTInputStream implements Closeable {

    /** NBT 递归深度上限，防止恶意深层嵌套导致 StackOverflowError。 */
    private static final int MAX_DEPTH = 64;
    /** TAG_Byte_Array 长度上限（16 MiB），防止恶意巨型分配导致 OutOfMemoryError。 */
    private static final int MAX_BYTE_ARRAY_LENGTH = 1 << 24;
    /** TAG_String 字节长度上限。 */
    private static final int MAX_STRING_LENGTH = 1 << 16;
    /** TAG_List 元素数量上限。 */
    private static final int MAX_LIST_LENGTH = 1 << 20;
    /** 名称字节长度上限。 */
    private static final int MAX_NAME_LENGTH = 1 << 14;

    /**
     * The data input stream.
     */
    private final DataInputStream is;

    /**
     * Creates a new <code>NBTInputStream</code>, which will source its data
     * from the specified input stream.
     *
     * @param is The input stream.
     * @throws IOException if an I/O error occurs.
     */
    public NBTInputStream(InputStream is) throws IOException {
        this.is = new DataInputStream(new GZIPInputStream(is));
    }

    /**
     * Reads an NBT tag from the stream.
     *
     * @return The tag that was read.
     * @throws IOException if an I/O error occurs.
     */
    public Tag readTag() throws IOException {
        return readTag(0);
    }

    /**
     * Reads an NBT from the stream.
     *
     * @param depth The depth of this tag.
     * @return The tag that was read.
     * @throws IOException if an I/O error occurs.
     */
    private Tag readTag(int depth) throws IOException {
        int type = is.readByte() & 0xFF;

        String name;
        if (type != NBTConstants.TYPE_END) {
            int nameLength = is.readShort() & 0xFFFF;
            if (nameLength > MAX_NAME_LENGTH) {
                throw new IOException("NBT name length too large: " + nameLength);
            }
            byte[] nameBytes = new byte[nameLength];
            is.readFully(nameBytes);
            name = new String(nameBytes, NBTConstants.CHARSET);
        } else {
            name = "";
        }

        return readTagPayload(type, name, depth);
    }

    /**
     * Reads the payload of a tag, given the name and type.
     *
     * @param type  The type.
     * @param name  The name.
     * @param depth The depth.
     * @return The tag.
     * @throws IOException if an I/O error occurs.
     */
    private Tag readTagPayload(int type, String name, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT nesting depth exceeded maximum of " + MAX_DEPTH);
        }

        switch (type) {
            case NBTConstants.TYPE_END:
                if (depth == 0) {
                    throw new IOException("TAG_End found without a TAG_Compound/TAG_List tag preceding it.");
                } else {
                    return new EndTag();
                }
            case NBTConstants.TYPE_BYTE:
                return new ByteTag(name, is.readByte());
            case NBTConstants.TYPE_SHORT:
                return new ShortTag(name, is.readShort());
            case NBTConstants.TYPE_INT:
                return new IntTag(name, is.readInt());
            case NBTConstants.TYPE_LONG:
                return new LongTag(name, is.readLong());
            case NBTConstants.TYPE_FLOAT:
                return new FloatTag(name, is.readFloat());
            case NBTConstants.TYPE_DOUBLE:
                return new DoubleTag(name, is.readDouble());
            case NBTConstants.TYPE_BYTE_ARRAY:
                int length = is.readInt();
                if (length < 0 || length > MAX_BYTE_ARRAY_LENGTH) {
                    throw new IOException("NBT byte array length out of range: " + length);
                }
                byte[] bytes = new byte[length];
                is.readFully(bytes);
                return new ByteArrayTag(name, bytes);
            case NBTConstants.TYPE_STRING:
                length = is.readShort() & 0xFFFF;
                if (length > MAX_STRING_LENGTH) {
                    throw new IOException("NBT string length too large: " + length);
                }
                bytes = new byte[length];
                is.readFully(bytes);
                return new StringTag(name, new String(bytes, NBTConstants.CHARSET));
            case NBTConstants.TYPE_LIST:
                int childType = is.readByte();
                length = is.readInt();
                if (length < 0 || length > MAX_LIST_LENGTH) {
                    throw new IOException("NBT list length out of range: " + length);
                }

                List<Tag> tagList = new ArrayList<>(Math.min(length, 64));
                for (int i = 0; i < length; i++) {
                    Tag tag = readTagPayload(childType, "", depth + 1);
                    if (tag instanceof EndTag) {
                        throw new IOException("TAG_End not permitted in a list.");
                    }
                    tagList.add(tag);
                }

                return new ListTag(name, NBTUtils.getTypeClass(childType), tagList);
            case NBTConstants.TYPE_COMPOUND:
                Map<String, Tag> tagMap = new HashMap<>();
                while (true) {
                    Tag tag = readTag(depth + 1);
                    if (tag instanceof EndTag) {
                        break;
                    } else {
                        tagMap.put(tag.getName(), tag);
                    }
                }

                return new CompoundTag(name, tagMap);
            default:
                throw new IOException("Invalid tag type: " + type + ".");
        }
    }

    @Override
    public void close() throws IOException {
        is.close();
    }

}
