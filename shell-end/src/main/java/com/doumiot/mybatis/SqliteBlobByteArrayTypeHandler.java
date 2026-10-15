package com.doumiot.mybatis;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * SQLite JDBC 对 {@link ResultSet#getBytes(int)} 未完整实现，通过流读取 BLOB。
 */
@MappedTypes(byte[].class)
@MappedJdbcTypes({JdbcType.BLOB, JdbcType.BINARY, JdbcType.VARBINARY, JdbcType.LONGVARBINARY})
public class SqliteBlobByteArrayTypeHandler extends BaseTypeHandler<byte[]> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, byte[] parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setBytes(i, parameter);
    }

    @Override
    public byte[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return readStream(rs.getBinaryStream(columnName));
    }

    @Override
    public byte[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return readStream(rs.getBinaryStream(columnIndex));
    }

    @Override
    public byte[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        Blob blob = cs.getBlob(columnIndex);
        if (blob == null) {
            return null;
        }
        try {
            try (InputStream in = blob.getBinaryStream()) {
                return readStream(in);
            }
        } catch (IOException e) {
            throw new SQLException("Failed to read BLOB", e);
        } finally {
            blob.free();
        }
    }

    private static byte[] readStream(InputStream in) throws SQLException {
        if (in == null) {
            return null;
        }
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = stream.read(buf)) >= 0) {
                if (n > 0) {
                    out.write(buf, 0, n);
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new SQLException("Failed to read BLOB", e);
        }
    }
}
