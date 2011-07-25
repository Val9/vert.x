package tests.core.net;

import org.nodex.core.DoneHandler;
import org.nodex.core.Nodex;
import org.nodex.core.buffer.Buffer;
import org.nodex.core.buffer.DataHandler;
import org.nodex.core.net.NetClient;
import org.nodex.core.net.NetConnectHandler;
import org.nodex.core.net.NetServer;
import org.nodex.core.net.NetSocket;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import tests.Utils;
import tests.core.TestBase;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * User: timfox
 * Date: 21/07/2011
 * Time: 13:24
 * <p/>
 * Test that the node.x threading model is obeyed
 * <p/>
 * TODO - maybe we should just do this in NetTest?
 */
public class ThreadingTest extends TestBase {

  @BeforeClass
  public void setUp() {
  }

  @AfterClass
  public void tearDown() {
  }

  @Test
  // Test that all handlers for a connection are executed with same context
  public void testNetHandlers() throws Exception {
    final int dataLength = 10000;
    final CountDownLatch serverClosedLatch = new CountDownLatch(1);
    NetServer server = NetServer.createServer(new NetConnectHandler() {
      public void onConnect(final NetSocket sock) {
        final ContextChecker checker = new ContextChecker();
        sock.data(new DataHandler() {
          public void onData(Buffer data) {
            checker.check();
            sock.write(data);    // Send it back to client
          }
        });
        sock.closed(new DoneHandler() {
          public void onDone() {
            checker.check();
            serverClosedLatch.countDown();
          }
        });
      }
    }).listen(8181);

    NetClient client = NetClient.createClient();

    final CountDownLatch clientClosedLatch = new CountDownLatch(1);
    client.connect(8181, new NetConnectHandler() {
      public void onConnect(final NetSocket sock) {
        final ContextChecker checker = new ContextChecker();
        final Buffer buff = Buffer.newDynamic(0);
        sock.data(new DataHandler() {
          public void onData(Buffer data) {
            checker.check();
            buff.append(data);
            if (buff.length() == dataLength) {
              sock.close();
            }
          }
        });
        sock.closed(new DoneHandler() {
          public void onDone() {
            checker.check();
            clientClosedLatch.countDown();
          }
        });
        Buffer sendBuff = Utils.generateRandomBuffer(dataLength);
        sock.write(sendBuff);
        sock.close();
      }
    });

    assert serverClosedLatch.await(5, TimeUnit.SECONDS);
    assert clientClosedLatch.await(5, TimeUnit.SECONDS);

    awaitClose(server);
  }

  @Test
  /* Test that event loops are shared across available connections */
  public void testMultipleEventLoops() throws Exception {
    int loops = Nodex.instance.getCoreThreadPoolSize();
    int connections = 100;
    final Map<Thread, Object> threads = new ConcurrentHashMap<Thread, Object>();
    final CountDownLatch serverConnectLatch = new CountDownLatch(connections);
    NetServer server = NetServer.createServer(new NetConnectHandler() {
      public void onConnect(NetSocket sock) {
        threads.put(Thread.currentThread(), "foo");
        serverConnectLatch.countDown();
      }
    }).listen(8181);

    final CountDownLatch clientConnectLatch = new CountDownLatch(loops);
    NetClient client = NetClient.createClient();

    for (int i = 0; i < connections; i++) {
      client.connect(8181, new NetConnectHandler() {
        public void onConnect(NetSocket sock) {
          clientConnectLatch.countDown();
          sock.close();
        }
      });
    }

    assert serverConnectLatch.await(5, TimeUnit.SECONDS);
    assert clientConnectLatch.await(5, TimeUnit.SECONDS);
    assert loops == threads.size();

    awaitClose(server);
  }
}