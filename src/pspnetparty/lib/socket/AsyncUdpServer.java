/*
Copyright (C) 2011 monte

This file is part of PSP NetParty.

PSP NetParty is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package pspnetparty.lib.socket;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import pspnetparty.lib.Utility;
import pspnetparty.lib.constants.AppConstants;

public class AsyncUdpServer implements IServer {

	private static final int READ_BUFFER_SIZE = 20000;

	private Selector selector;
	private ConcurrentHashMap<IServerListener, Object> serverListeners;

	private DatagramChannel serverChannel;
	private ByteBuffer readBuffer = ByteBuffer.allocateDirect(READ_BUFFER_SIZE);
	private PacketData data = new PacketData(readBuffer);

	private SelectionKey selectionKey;
	private SendBufferQueue<InetSocketAddress> sendBufferQueue = new SendBufferQueue<InetSocketAddress>(100000);

	private HashMap<String, IProtocol> protocolHandlers = new HashMap<String, IProtocol>();
	private ConcurrentHashMap<InetSocketAddress, Connection> establishedConnections;
	private ConcurrentHashMap<Connection, Connection> toBeClosedConnections;

	private ByteBuffer bufferProtocolOK = AppConstants.CHARSET.encode(IProtocol.PROTOCOL_OK);
	private ByteBuffer bufferProtocolNG = AppConstants.CHARSET.encode(IProtocol.PROTOCOL_NG);
	private ByteBuffer bufferProtocolNumber = AppConstants.CHARSET.encode(IProtocol.NUMBER);
	private ByteBuffer terminateBuffer = ByteBuffer.wrap(new byte[] { 0 });

	private Thread selectorThread;
	private Thread keepAliveThread;

	public AsyncUdpServer() {
		serverListeners = new ConcurrentHashMap<IServerListener, Object>();
		establishedConnections = new ConcurrentHashMap<InetSocketAddress, Connection>(30, 0.75f, 3);
		toBeClosedConnections = new ConcurrentHashMap<Connection, Connection>();
	}

	@Override
	public void addServerListener(IServerListener listener) {
		serverListeners.put(listener, this);
	}

	@Override
	public void addProtocol(IProtocol handler) {
		protocolHandlers.put(handler.getProtocol(), handler);
	}

	@Override
	public boolean isListening() {
		return selector != null && selector.isOpen();
	}

	private void log(String message) {
		for (IServerListener listener : serverListeners.keySet())
			listener.log(message);
	}

	@Override
	public void startListening(InetSocketAddress bindAddress) throws IOException {
		if (isListening())
			stopListening();

		selector = Selector.open();

		serverChannel = DatagramChannel.open();
		serverChannel.configureBlocking(false);
		serverChannel.socket().bind(bindAddress);
		selectionKey = serverChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);

		log("UDP: Listening on " + bindAddress);

		selectorThread = new Thread(new Runnable() {
			@Override
			public void run() {
				for (IServerListener listener : serverListeners.keySet())
					listener.serverStartupFinished();

				selectorLoop();

				for (IServerListener listener : serverListeners.keySet()) {
					listener.log("UDP: Now shuting down...");
					listener.serverShutdownFinished();
				}
				keepAliveThread.interrupt();
			}
		});
		selectorThread.setName(getClass().getName() + " Selector");
		selectorThread.setDaemon(true);
		selectorThread.start();

		keepAliveThread = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					keepAliveLoop();
				} catch (InterruptedException e) {
				}
			}
		});
		keepAliveThread.setName(getClass().getName() + " KeepAlive");
		keepAliveThread.setDaemon(true);
		keepAliveThread.start();
	}

	private void selectorLoop() {
		try {
			while (serverChannel.isOpen())
				while (selector.select() > 0) {
					for (Iterator<SelectionKey> it = selector.selectedKeys().iterator(); it.hasNext();) {
						SelectionKey key = it.next();
						it.remove();

						try {
							if (key.isReadable()) {
								readBuffer.clear();
								InetSocketAddress remoteAddress = (InetSocketAddress) serverChannel.receive(readBuffer);
								if (remoteAddress == null) {
									continue;
								}
								readBuffer.flip();

								Connection conn = establishedConnections.get(remoteAddress);
								if (conn == null) {
									String message = data.getMessage();
									String[] tokens = message.split(IProtocol.SEPARATOR);
									if (tokens.length != 2) {
										bufferProtocolNG.position(0);
										addToSendQueue(bufferProtocolNG, remoteAddress);
										continue;
									}

									String protocol = tokens[0];
									String number = tokens[1];

									IProtocol handler = protocolHandlers.get(protocol);
									if (handler == null) {
										bufferProtocolNG.position(0);
										addToSendQueue(bufferProtocolNG, remoteAddress);
										continue;
									}

									conn = new Connection(remoteAddress);
									if (!number.equals(IProtocol.NUMBER)) {
										bufferProtocolNumber.position(0);
										conn.send(bufferProtocolNumber);
										continue;
									}

									bufferProtocolOK.position(0);
									conn.send(bufferProtocolOK);

									conn.driver = handler.createDriver(conn);
									if (conn.driver == null) {
										terminateBuffer.position(0);
										conn.send(terminateBuffer);
										continue;
									}

									establishedConnections.put(remoteAddress, conn);
								} else if (!toBeClosedConnections.containsKey(conn)) {
									conn.processData();
								}
							} else if (key.isWritable()) {
								SendBufferQueue<InetSocketAddress>.Allotment allot = sendBufferQueue.poll();
								if (allot == null) {
									for (Connection conn : toBeClosedConnections.keySet()) {
										conn.disconnect();
									}
									toBeClosedConnections.clear();

									key.interestOps(SelectionKey.OP_READ);
								} else {
									serverChannel.send(allot.getBuffer(), allot.getAttachment());
								}
							}
						} catch (Exception e) {
						}
					}
				}
		} catch (CancelledKeyException e) {
		} catch (ClosedSelectorException e) {
		} catch (IOException e) {
		} catch (RuntimeException e) {
			for (IServerListener listener : serverListeners.keySet())
				listener.log(Utility.stackTraceToString(e));
		}
	}

	private void keepAliveLoop() throws InterruptedException {
		ByteBuffer keepAliveBuffer = ByteBuffer.wrap(new byte[] { 1 });
		while (isListening()) {
			long deadline = System.currentTimeMillis() - IProtocol.KEEPALIVE_DEADLINE;

			for (Entry<InetSocketAddress, Connection> entry : establishedConnections.entrySet()) {
				try {
					Connection conn = entry.getValue();
					if (conn.lastKeepAliveReceived < deadline) {
						log(Utility.makeKeepAliveDisconnectLog("UDP", conn.remoteAddress, deadline, conn.lastKeepAliveReceived));
						conn.disconnect();
					} else {
						keepAliveBuffer.clear();
						conn.send(keepAliveBuffer);
					}
				} catch (RuntimeException e) {
					log(Utility.stackTraceToString(e));
				} catch (Exception e) {
					log(Utility.stackTraceToString(e));
				}
			}

			Thread.sleep(IProtocol.KEEPALIVE_INTERVAL);
		}
	}

	@Override
	public void stopListening() {
		if (!isListening())
			return;

		try {
			selector.close();
		} catch (IOException e) {
		}

		if (serverChannel != null && serverChannel.isOpen()) {
			try {
				serverChannel.close();
			} catch (IOException e) {
			}
		}
		for (Entry<InetSocketAddress, Connection> entry : establishedConnections.entrySet()) {
			Connection conn = entry.getValue();
			conn.disconnect();
		}
	}

	private void addToSendQueue(ByteBuffer buffer, InetSocketAddress address) {
		sendBufferQueue.queue(buffer, false, address);

		try {
			if (selectionKey != null) {
				selector.wakeup();
				selectionKey.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
			}
		} catch (CancelledKeyException e) {
		}
	}

	private class Connection implements ISocketConnection {
		private InetSocketAddress remoteAddress;
		private IProtocolDriver driver;

		private long lastKeepAliveReceived;

		public Connection(InetSocketAddress remoteAddress) {
			this.remoteAddress = remoteAddress;
			lastKeepAliveReceived = System.currentTimeMillis();
		}

		@Override
		public InetSocketAddress getRemoteAddress() {
			return remoteAddress;
		}

		@Override
		public InetSocketAddress getLocalAddress() {
			return (InetSocketAddress) serverChannel.socket().getLocalSocketAddress();
		}

		@Override
		public boolean isConnected() {
			return serverChannel.isConnected();
		}

		@Override
		public void disconnect() {
			if (establishedConnections.remove(remoteAddress) == null)
				return;

			ByteBuffer terminateBuffer = ByteBuffer.wrap(new byte[] { 0 });
			send(terminateBuffer);

			if (driver != null) {
				driver.connectionDisconnected();
				driver = null;
			}

			lastKeepAliveReceived = 0;
		}

		private void processData() {
			if (readBuffer.limit() == 1) {
				switch (readBuffer.get(0)) {
				case 0:
					if (sendBufferQueue.isEmpty())
						disconnect();
					else
						toBeClosedConnections.put(this, this);
					return;
				case 1:
					lastKeepAliveReceived = System.currentTimeMillis();
					return;
				}
			}

			boolean sessionContinue = false;
			try {
				sessionContinue = driver.process(data);
			} catch (Exception e) {
			}

			if (!sessionContinue) {
				disconnect();
			}
		}

		@Override
		public void send(ByteBuffer buffer) {
			addToSendQueue(buffer, remoteAddress);
		}
	}

	public static void main(String[] args) throws IOException {
		InetSocketAddress address = new InetSocketAddress(30000);
		AsyncUdpServer server = new AsyncUdpServer();
		server.addServerListener(new IServerListener() {
			@Override
			public void log(String message) {
				System.out.println(message);
			}

			@Override
			public void serverStartupFinished() {
			}

			@Override
			public void serverShutdownFinished() {
			}
		});

		server.addProtocol(new IProtocol() {
			@Override
			public void log(String message) {
				System.out.println(message);
			}

			@Override
			public String getProtocol() {
				return "TEST";
			}

			@Override
			public IProtocolDriver createDriver(final ISocketConnection connection) {
				System.out.println(connection.getRemoteAddress() + " [接続されました]");

				Thread pingThread = new Thread(new Runnable() {
					@Override
					public void run() {
						try {
							Thread.sleep(20000);
							System.out.println("Send PING");
							connection.send(Utility.encode("PING"));
						} catch (InterruptedException e) {
						}
					}
				});
				pingThread.setDaemon(true);
				// pingThread.start();

				return new IProtocolDriver() {
					@Override
					public boolean process(PacketData data) {
						String remoteAddress = connection.getRemoteAddress().toString();
						String message = data.getMessage();

						System.out.println(remoteAddress + " >" + message);
						connection.send(Utility.encode(message));

						return true;
					}

					@Override
					public ISocketConnection getConnection() {
						return connection;
					}

					@Override
					public void connectionDisconnected() {
						System.out.println(connection.getRemoteAddress() + " [切断されました]");
					}

					@Override
					public void errorProtocolNumber(String number) {
					}
				};
			}
		});

		server.startListening(address);

		while (System.in.read() != '\n') {
		}

		server.stopListening();
	}
}
