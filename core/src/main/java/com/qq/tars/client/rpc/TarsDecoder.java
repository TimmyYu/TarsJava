package com.qq.tars.client.rpc;

import com.qq.tars.common.support.ClassLoaderManager;
import com.qq.tars.common.support.Holder;
import com.qq.tars.common.util.CommonUtils;
import com.qq.tars.common.util.Constants;
import com.qq.tars.common.util.StringUtils;
import com.qq.tars.net.core.IoBuffer;
import com.qq.tars.net.core.Request;
import com.qq.tars.net.core.Response;
import com.qq.tars.net.core.Session;
import com.qq.tars.protocol.tars.TarsInputStream;
import com.qq.tars.protocol.tars.support.TarsMethodInfo;
import com.qq.tars.protocol.tars.support.TarsMethodParameterInfo;
import com.qq.tars.protocol.util.TarsHelper;
import com.qq.tars.protocol.util.TarsUtil;
import com.qq.tars.rpc.protocol.ServantRequest;
import com.qq.tars.rpc.protocol.ServantResponse;
import com.qq.tars.rpc.protocol.tars.TarsServantRequest;
import com.qq.tars.rpc.protocol.tars.TarsServantResponse;
import com.qq.tars.rpc.protocol.tars.support.AnalystManager;
import com.qq.tars.rpc.protocol.tup.UniAttribute;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.lang.reflect.Method;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class TarsDecoder extends ByteToMessageDecoder {

    public TarsDecoder() {
    }

    public String charsetName = Constants.default_charset_name;

    @Override
    protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf, List<Object> list) throws Exception {
        if (byteBuf.readableBytes() < 4) {
            return;
        }
        int beginIndex = byteBuf.readerIndex();
        int length = byteBuf.readInt();
        if (byteBuf.readableBytes() < length) {
            byteBuf.readerIndex(beginIndex);
            return;
        }
        byte[] array = new byte[length];
        byteBuf.readBytes(array);
        list.add(new Object());
    }

    public Request decodeRequest(IoBuffer buffer, Session session) throws ProtocolException {
        if (buffer.remaining() < 4) {
            return null;
        }
        int length = buffer.getInt() - TarsHelper.HEAD_SIZE;
        if (length > TarsHelper.PACKAGE_MAX_LENGTH || length <= 0) {
            throw new ProtocolException("the length header of the package must be between 0~10M bytes. data length:" + Integer.toHexString(length));
        }
        if (buffer.remaining() < length) {
            return null;
        }

        byte[] reads = new byte[length];
        buffer.get(reads);
        TarsInputStream jis = new TarsInputStream(reads);
        TarsServantRequest request = new TarsServantRequest(session);
        try {
            short version = jis.read(TarsHelper.STAMP_SHORT.shortValue(), 1, true);
            byte packetType = jis.read(TarsHelper.STAMP_BYTE.byteValue(), 2, true);
            int messageType = jis.read(TarsHelper.STAMP_INT.intValue(), 3, true);
            int requestId = jis.read(TarsHelper.STAMP_INT.intValue(), 4, true);
            String servantName = jis.readString(5, true);
            String methodName = jis.readString(6, true);
            request.setVersion(version);
            request.setPacketType(packetType);
            request.setMessageType(messageType);
            request.setRequestId(requestId);
            request.setServantName(servantName);
            request.setFunctionName(methodName);
            request.setInputStream(jis);
            request.setCharsetName(charsetName);
        } catch (Exception e) {
            System.err.println(e);
            request.setRet(TarsHelper.SERVERDECODEERR);
        }
        return request;
    }

    public ServantRequest decodeRequestBody(ServantRequest req) {
        TarsServantRequest request = (TarsServantRequest) req;
        if (request.getRet() != TarsHelper.SERVERSUCCESS) {
            return request;
        }
        if (TarsHelper.isPing(request.getFunctionName())) {
            return request;
        }

        TarsInputStream jis = request.getInputStream();
        ClassLoader oldClassLoader = null;
        try {
            oldClassLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(resolveProtocolClassLoader());
            String methodName = request.getFunctionName();
            byte[] data = jis.read(TarsHelper.STAMP_BYTE_ARRAY, 7, true);//数据
            int timeout = jis.read(TarsHelper.STAMP_INT.intValue(), 8, true);//超时时间
            Map<String, String> context = (Map<String, String>) jis.read(TarsHelper.STAMP_MAP, 9, true);//Map<String, String> context
            Map<String, String> status = (Map<String, String>) jis.read(TarsHelper.STAMP_MAP, 10, true);

            request.setTimeout(timeout);
            request.setContext(context);
            request.setStatus(status);

            String servantName = request.getServantName();
            Map<String, TarsMethodInfo> methodInfoMap = AnalystManager.getInstance().getMethodMapByName(servantName);

            if (methodInfoMap == null || methodInfoMap.isEmpty()) {
                request.setRet(TarsHelper.SERVERNOSERVANTERR);
                throw new ProtocolException("no found methodInfo, the context[ROOT], serviceName[" + servantName + "], methodName[" + methodName + "]");
            }

            TarsMethodInfo methodInfo = methodInfoMap.get(methodName);
            if (methodInfo == null) {
                request.setRet(TarsHelper.SERVERNOFUNCERR);
                throw new ProtocolException("no found methodInfo, the context[ROOT], serviceName[" + servantName + "], methodName[" + methodName + "]");
            }

            request.setMethodInfo(methodInfo);
            List<TarsMethodParameterInfo> parametersList = methodInfo.getParametersList();
            if (!CommonUtils.isEmptyCollection(parametersList)) {
                Object[] parameters = new Object[parametersList.size()];
                int i = 0;
                if (TarsHelper.VERSION == request.getVersion()) {//request
                    parameters = decodeRequestBody(data, request.getCharsetName(), methodInfo);
                } else if (TarsHelper.VERSION2 == request.getVersion() || TarsHelper.VERSION3 == request.getVersion()) {
                    //wup request
                    UniAttribute unaIn = new UniAttribute();
                    unaIn.setEncodeName(request.getCharsetName());

                    if (request.getVersion() == TarsHelper.VERSION2) {
                        unaIn.decodeVersion2(data);
                    } else if (request.getVersion() == TarsHelper.VERSION3) {
                        unaIn.decodeVersion3(data);
                    }

                    Object value = null;
                    for (TarsMethodParameterInfo parameterInfo : parametersList) {
                        if (TarsHelper.isHolder(parameterInfo.getAnnotations())) {
                            String holderName = TarsHelper.getHolderName(parameterInfo.getAnnotations());
                            if (!StringUtils.isEmpty(holderName)) {
                                value = new Holder<Object>(unaIn.getByClass(holderName, parameterInfo.getStamp()));
                            } else {
                                value = new Holder<Object>();
                            }
                        } else {
                            value = unaIn.getByClass(parameterInfo.getName(), parameterInfo.getStamp());
                        }
                        parameters[i++] = value;
                    }
                } else {
                    request.setRet(TarsHelper.SERVERDECODEERR);
                    System.err.println("un supported protocol, ver=" + request.getVersion());
                }
                request.setMethodParameters(parameters);
            }
        } catch (Throwable ex) {
            if (request.getRet() == TarsHelper.SERVERSUCCESS) {
                request.setRet(TarsHelper.SERVERDECODEERR);
            }
            System.err.println(TarsUtil.getHexdump(jis.getBs()));
        } finally {
            if (oldClassLoader != null) {
                Thread.currentThread().setContextClassLoader(oldClassLoader);
            }
        }
        return request;
    }

    protected Object[] decodeRequestBody(byte[] data, String charset, TarsMethodInfo methodInfo) throws Exception {
        TarsInputStream jis = new TarsInputStream(data);
        List<TarsMethodParameterInfo> parametersList = methodInfo.getParametersList();
        Object[] parameters = new Object[parametersList.size()];
        int i = 0;

        jis.setServerEncoding(charset);//set decode charset name
        Object value = null;
        for (TarsMethodParameterInfo parameterInfo : parametersList) {
            if (TarsHelper.isHolder(parameterInfo.getAnnotations())) {
                value = new Holder<Object>(jis.read(parameterInfo.getStamp(), parameterInfo.getOrder(), false));
            } else {
                value = jis.read(parameterInfo.getStamp(), parameterInfo.getOrder(), false);
            }
            parameters[i++] = value;
        }
        return parameters;
    }

    public Response decodeResponse(IoBuffer buffer, Session session) throws com.qq.tars.net.protocol.ProtocolException {
        if (buffer.remaining() < TarsHelper.HEAD_SIZE) {
            return null;
        }
        int length = buffer.getInt() - TarsHelper.HEAD_SIZE;
        if (length > TarsHelper.PACKAGE_MAX_LENGTH || length <= 0) {
            throw new com.qq.tars.net.protocol.ProtocolException("the length header of the package must be between 0~10M bytes. data length:" + Integer.toHexString(length));
        }
        if (buffer.remaining() < length) {
            return null;
        }

        byte[] bytes = new byte[length];
        buffer.get(bytes);

        TarsServantResponse response = new TarsServantResponse(session);
        response.setCharsetName(charsetName);

        TarsInputStream is = new TarsInputStream(bytes);
        is.setServerEncoding(charsetName);

        response.setVersion(is.read((short) 0, 1, true));
        response.setPacketType(is.read((byte) 0, 2, true));
        response.setRequestId(is.read(0, 3, true));
        response.setMessageType(is.read(0, 4, true));
        response.setRet(is.read(0, 5, true));
        if (response.getRet() == TarsHelper.SERVERSUCCESS) {
            response.setInputStream(is);
        }
        return response;
    }

    public void decodeResponseBody(ServantResponse resp) throws com.qq.tars.net.protocol.ProtocolException {
        TarsServantResponse response = (TarsServantResponse) resp;

        TarsServantRequest request = response.getRequest();
        if (request.isAsync()) {
            return;
        }
        TarsInputStream is = response.getInputStream();

        byte[] data = is.read(new byte[]{}, 6, true);
        TarsInputStream jis = new TarsInputStream(data);
        jis.setServerEncoding(response.getCharsetName());

        TarsMethodInfo methodInfo = request.getMethodInfo();
        TarsMethodParameterInfo returnInfo = methodInfo.getReturnInfo();

        Object[] results;
        try {
            results = decodeResponseBody(data, response.getCharsetName(), methodInfo);
        } catch (Exception e) {
            throw new com.qq.tars.net.protocol.ProtocolException(e);
        }

        int i = 0;
        if (returnInfo != null && Void.TYPE != returnInfo.getType()) {
            response.setResult(results[i++]);
        }

        List<TarsMethodParameterInfo> list = methodInfo.getParametersList();
        for (TarsMethodParameterInfo info : list) {
            if (!TarsHelper.isHolder(info.getAnnotations())) {
                continue;
            }
            try {
                TarsHelper.setHolderValue(request.getMethodParameters()[info.getOrder() - 1], results[i++]);
            } catch (Exception e) {
                throw new com.qq.tars.net.protocol.ProtocolException(e);
            }
        }
        response.setStatus((HashMap<String, String>) is.read(TarsHelper.STAMP_MAP, 7, false));
    }

    protected Object[] decodeResponseBody(byte[] data, String charset, TarsMethodInfo methodInfo) throws Exception {
        TarsMethodParameterInfo returnInfo = methodInfo.getReturnInfo();
        List<Object> values = new ArrayList<Object>();

        TarsInputStream jis = new TarsInputStream(data);
        jis.setServerEncoding(charset);

        if (returnInfo != null && Void.TYPE != returnInfo.getType()) {
            values.add(jis.read(returnInfo.getStamp(), returnInfo.getOrder(), true));
        }
        List<TarsMethodParameterInfo> list = methodInfo.getParametersList();
        for (TarsMethodParameterInfo info : list) {
            if (!TarsHelper.isHolder(info.getAnnotations())) {
                continue;
            }
            try {
                values.add(jis.read(info.getStamp(), info.getOrder(), true));
            } catch (Exception e) {
                throw new com.qq.tars.net.protocol.ProtocolException(e);
            }
        }
        return values.toArray();
    }

    public Object[] decodeCallbackArgs(TarsServantResponse response) throws com.qq.tars.net.protocol.ProtocolException {
        byte[] data = response.getInputStream().read(new byte[]{}, 6, true);

        TarsServantRequest request = response.getRequest();

        TarsMethodInfo methodInfo = null;
        Map<Method, TarsMethodInfo> map = AnalystManager.getInstance().getMethodMap(request.getApi());
        for (Iterator<Map.Entry<Method, TarsMethodInfo>> it = map.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Method, TarsMethodInfo> entry = it.next();
            if (entry.getKey().getName().equals(request.getFunctionName())) {
                methodInfo = entry.getValue();
            }
        }

        try {
            return decodeCallbackArgs(data, response.getCharsetName(), methodInfo);
        } catch (Exception e) {
            throw new com.qq.tars.net.protocol.ProtocolException(e);
        }
    }

    protected Object[] decodeCallbackArgs(byte[] data, String charset, TarsMethodInfo methodInfo) throws com.qq.tars.net.protocol.ProtocolException, NoSuchMethodException, Exception {
        TarsInputStream jis = new TarsInputStream(data);
        jis.setServerEncoding(charset);

        List<Object> list = new ArrayList<Object>();
        TarsMethodParameterInfo returnInfo = methodInfo.getReturnInfo();
        if (returnInfo != null && Void.TYPE != returnInfo.getType()) {
            list.add(jis.read(returnInfo.getStamp(), returnInfo.getOrder(), true));
        }

        List<TarsMethodParameterInfo> parameterInfoList = methodInfo.getParametersList();
        for (TarsMethodParameterInfo info : parameterInfoList) {
            if (TarsHelper.isContext(info.getAnnotations()) || TarsHelper.isCallback(info.getAnnotations())) {
                continue;
            }

            if (TarsHelper.isHolder(info.getAnnotations())) {
                list.add(jis.read(info.getStamp(), info.getOrder(), false));
            }
        }

        return list.toArray();
    }

    protected ClassLoader resolveProtocolClassLoader() {
        ClassLoader classLoader = ClassLoaderManager.getInstance().getClassLoader("");
        if (classLoader == null) {
            classLoader = Thread.currentThread().getContextClassLoader();
        }
        return classLoader;
    }

    public String getProtocol() {
        return Constants.TARS_PROTOCOL;
    }


}
