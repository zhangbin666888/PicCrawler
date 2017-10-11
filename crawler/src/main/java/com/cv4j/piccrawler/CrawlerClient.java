package com.cv4j.piccrawler;

import com.safframework.tony.common.utils.Preconditions;
import io.reactivex.*;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import org.apache.http.HttpEntity;
import org.apache.http.ParseException;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Created by tony on 2017/9/11.
 */
public class CrawlerClient {

    /**
     * 全局连接池对象
     */
    private static PoolingHttpClientConnectionManager connManager = null;
    private static AtomicInteger count = new AtomicInteger();

    private int timeOut;
    private int repeat = 1;
    private FileStrategy fileStrategy;

    /**
     * 配置连接池信息，支持http/https
     */
    static {
        SSLContext sslcontext = null;
        try {
            //获取TLS安全协议上下文
            sslcontext = SSLContext.getInstance("TLS");
            sslcontext.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] arg0, String arg1)
                        throws CertificateException {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] arg0, String arg1)
                        throws CertificateException {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[]{};
                }
            }}, null);

            SSLConnectionSocketFactory scsf = new SSLConnectionSocketFactory(sslcontext, NoopHostnameVerifier.INSTANCE);
            RequestConfig defaultConfig = RequestConfig.custom().setCookieSpec(CookieSpecs.STANDARD_STRICT)
                    .setExpectContinueEnabled(true)
                    .setTargetPreferredAuthSchemes(Arrays.asList(AuthSchemes.NTLM, AuthSchemes.DIGEST))
                    .setProxyPreferredAuthSchemes(Arrays.asList(AuthSchemes.BASIC)).build();
            Registry<ConnectionSocketFactory> sfr = RegistryBuilder.<ConnectionSocketFactory>create()
                    .register("http", PlainConnectionSocketFactory.INSTANCE)
                    .register("https", scsf).build();

            connManager = new PoolingHttpClientConnectionManager(sfr);

            // 设置最大连接数
            connManager.setMaxTotal(200);
            // 设置每个连接的路由数
            connManager.setDefaultMaxPerRoute(20);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (KeyManagementException e) {
            e.printStackTrace();
        }
    }

    private CrawlerClient() {
    }

    public static CrawlerClient get() {
        return Holder.CLIENT;
    }

    private static class Holder {
        private static final CrawlerClient CLIENT = new CrawlerClient();
    }

    /**
     * @param timeOut 设置超时时间
     * @return
     */
    public CrawlerClient timeOut(int timeOut) {

        this.timeOut = timeOut;
        return this;
    }

    /**
     * @param fileStrategy 设置生成文件的策略
     * @return
     */
    public CrawlerClient fileStrategy(FileStrategy fileStrategy) {

        this.fileStrategy = fileStrategy;
        return this;
    }

    /**
     * @param repeat 设置重复次数
     * @return
     */
    public CrawlerClient repeat(int repeat) {

        if (repeat > 0) {
            this.repeat = repeat;
        }

        return this;
    }

    /**
     * 获取Http客户端连接对象
     *
     * @param timeOut 超时时间
     * @return Http客户端连接对象
     */
    private CloseableHttpClient getHttpClient(int timeOut) {
        // 创建Http请求配置参数
        RequestConfig requestConfig = RequestConfig.custom()
                // 获取连接超时时间
                .setConnectionRequestTimeout(timeOut)
                // 请求超时时间
                .setConnectTimeout(timeOut)
                // 响应超时时间
                .setSocketTimeout(timeOut)
                .build();

        // 创建httpClient
        return HttpClients.custom()
                // 把请求相关的超时信息设置到连接客户端
                .setDefaultRequestConfig(requestConfig)
                // 把请求重试设置到连接客户端
                .setRetryHandler(new RetryHandler())
                // 配置连接池管理对象
                .setConnectionManager(connManager)
                .build();
    }

    /**
     * 下载图片
     *
     * @param url 图片地址
     * @return
     */
    public void downloadPic(String url) {

        for (int i = 0; i < repeat; i++) {
            doDownloadPic(url);
        }
    }

    /**
     * 具体实现图片下载的方法
     *
     * @param url
     */
    private void doDownloadPic(String url) {

        // 获取客户端连接对象
        CloseableHttpClient httpClient = getHttpClient(timeOut);
        // 创建GET请求对象
        HttpPost httpPost = new HttpPost(url);

        CloseableHttpResponse response = null;

        try {
            // 执行请求
            response = httpClient.execute(httpPost);
            // 获取响应实体
            HttpEntity entity = response.getEntity();

            InputStream is = entity.getContent();
            // 包装成高效流
            BufferedInputStream bis = new BufferedInputStream(is);

            if (fileStrategy == null) {
                fileStrategy = new FileStrategy() {
                    @Override
                    public String filePath() {
                        return "images";
                    }

                    @Override
                    public String picFormat() {
                        return "png";
                    }

                    @Override
                    public FileGenType genType() {

                        return FileGenType.RANDOM;
                    }
                };
            }

            String path = fileStrategy.filePath();
            String format = fileStrategy.picFormat();
            FileGenType fileGenType = fileStrategy.genType();

            File directory = null;
            // 写入本地文件
            if (Preconditions.isNotBlank(path)) {

                directory = new File(path);
                if (!directory.exists()) {

                    directory.mkdir();

                    if (!directory.exists() || !directory.isDirectory()) {
                        directory = new File("images");
                        if (!directory.exists()) {
                            directory.mkdir();
                        }
                    }
                }
            } else {
                directory = new File("images");
                if (!directory.exists()) {
                    directory.mkdir();
                }
            }

            String fileName = null;
            switch (fileGenType) {

                case RANDOM:
                    fileName = Utils.randomUUID();
                    break;

                case AUTO_INCREMENT:
                    count.incrementAndGet();
                    fileName = String.valueOf(count.get());
                    break;
            }

            File file = new File(directory, fileName + "." + format);

            BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file));

            byte[] byt = new byte[1024 * 8];
            Integer len = -1;
            while ((len = bis.read(byt)) != -1) {
                bos.write(byt, 0, len);
            }

            bos.close();
            bis.close();

        } catch (ClientProtocolException e) {
            System.err.println("协议错误");
            e.printStackTrace();
        } catch (ParseException e) {
            System.err.println("解析错误");
            e.printStackTrace();
        } catch (IOException e) {
            System.err.println("IO错误");
            e.printStackTrace();
        } finally {
            if (response != null) {
                try {
                    EntityUtils.consume(response.getEntity());
                    response.close();
                } catch (IOException e) {
                    System.err.println("释放链接错误");
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 下载图片
     *
     * @param url 图片地址
     * @return
     */
    public void downloadPicUseRx(String url) {

        Flowable.create(new FlowableOnSubscribe<String>() {

            @Override
            public void subscribe(FlowableEmitter<String> e) throws Exception {

                for (int i = 0; i < repeat; i++) {

                    e.onNext(url);
                }
            }
        }, BackpressureStrategy.BUFFER)
                .map(new Function<String, WrapResponse>() {

                    @Override
                    public WrapResponse apply(String s) throws Exception {

                        // 获取客户端连接对象
                        CloseableHttpClient httpClient = getHttpClient(timeOut);
                        // 创建GET请求对象
                        HttpPost httpPost = new HttpPost(url);

                        CloseableHttpResponse response = null;

                        InputStream is = null;

                        try {
                            // 执行请求
                            response = httpClient.execute(httpPost);
                            // 获取响应实体
                            HttpEntity entity = response.getEntity();

                            is = entity.getContent();

                        } catch (ClientProtocolException e) {
                            System.err.println("协议错误");
                            e.printStackTrace();
                        } catch (ParseException e) {
                            System.err.println("解析错误");
                            e.printStackTrace();
                        } catch (IOException e) {
                            System.err.println("IO错误");
                            e.printStackTrace();
                        }

                        return new WrapResponse(response, is);
                    }
                })
                .observeOn(Schedulers.io())
                .map(new Function<WrapResponse, File>() {

                    @Override
                    public File apply(WrapResponse wrapResponse) throws Exception {

                        // 包装成高效流
                        BufferedInputStream bis = new BufferedInputStream(wrapResponse.is);

                        if (fileStrategy == null) {
                            fileStrategy = new FileStrategy() {
                                @Override
                                public String filePath() {
                                    return "images";
                                }

                                @Override
                                public String picFormat() {
                                    return "png";
                                }

                                @Override
                                public FileGenType genType() {

                                    return FileGenType.RANDOM;
                                }
                            };
                        }

                        String path = fileStrategy.filePath();
                        String format = fileStrategy.picFormat();
                        FileGenType fileGenType = fileStrategy.genType();

                        File directory = null;
                        // 写入本地文件
                        if (Preconditions.isNotBlank(path)) {

                            directory = new File(path);
                            if (!directory.exists()) {

                                directory.mkdir();

                                if (!directory.exists() || !directory.isDirectory()) {
                                    directory = new File("images");
                                    if (!directory.exists()) {
                                        directory.mkdir();
                                    }
                                }
                            }
                        } else {
                            directory = new File("images");
                            if (!directory.exists()) {
                                directory.mkdir();
                            }
                        }

                        String fileName = null;
                        switch (fileGenType) {

                            case RANDOM:
                                fileName = Utils.randomUUID();
                                break;

                            case AUTO_INCREMENT:
                                count.incrementAndGet();
                                fileName = String.valueOf(count.get());
                                break;
                        }

                        File file = new File(directory, fileName + "." + format);

                        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file));

                        byte[] byt = new byte[1024 * 8];
                        Integer len = -1;
                        while ((len = bis.read(byt)) != -1) {
                            bos.write(byt, 0, len);
                        }

                        bos.close();
                        bis.close();

                        if (wrapResponse.response != null) {
                            try {
                                EntityUtils.consume(wrapResponse.response.getEntity());
                                wrapResponse.response.close();
                            } catch (IOException e) {
                                System.err.println("释放链接错误");
                                e.printStackTrace();
                            }
                        }

                        return file;
                    }
                }).subscribe();
    }
}
