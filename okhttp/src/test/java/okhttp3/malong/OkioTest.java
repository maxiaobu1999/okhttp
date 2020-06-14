/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.malong;


import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;

import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import okio.Sink;
import okio.Source;


public final class OkioTest {
    public static final String TAG = "【OkioTest】";

    /**
     * 读取file中字符串
     *
     * @throws Exception e
     */
    @Test
    public void readString() throws Exception {
        String fileName = "mapping.txt";
        String path = this.getClass().getClassLoader().getResource("file").getPath();
        File file = new File(path, fileName);

        // 1.调用Okio类的静态方法获取Source(Sink)
        Source source = Okio.source(file);
        // 2.调用Okio类库的静态方法，通过刚才获取的Source(Sink)获取BufferedSink
        BufferedSource bufferedSource = Okio.buffer(source);
        // 3.对缓冲区根据实际需求做相应操作
        String read = bufferedSource.readString(Charset.forName("UTF-8"));
        System.out.println(TAG + "equalsAndHashcode():" + read);
        //4.若是Source，须将调用flush()
        // 5.最后close掉，避免内存泄漏
        bufferedSource.close();
        //    assertThat(b).isEqualTo(a);
//    assertThat(b.hashCode()).isEqualTo(a.hashCode());
    }

    /**
     * 向file中写字符串
     *
     * @throws Exception e
     */
    @Test
    public void writeString() throws Exception {
        String fileName = "write_string.txt";
        String path = this.getClass().getClassLoader().getResource("file").getPath();
        // /Users/v_maqinglong/Documents/AndroidProject/okhttp/okhttp/build/resources/test/file/write_string.txt
        File file = new File(path, fileName);
        @SuppressWarnings("unused") boolean delete = file.delete();
        @SuppressWarnings("unused")  boolean newFile = file.createNewFile();

        Sink sink = Okio.sink(file);
        BufferedSink bufferedSink = Okio.buffer(sink);
        bufferedSink.writeInt(90002);
        bufferedSink.writeString("aaa12352345234523452233asdfasdasdfas大家可能觉得我举的例子有些太简单了，好吧，我来说一个难的。让byte变量b等于-1。",
                Charset.forName("UTF-8"));
        //4.若是Source，须将调用flush()
        bufferedSink.flush();
        // 5.最后close掉，避免内存泄漏
        bufferedSink.close();
        //    assertThat(b).isEqualTo(a);
//    assertThat(b.hashCode()).isEqualTo(a.hashCode());
    }


}
