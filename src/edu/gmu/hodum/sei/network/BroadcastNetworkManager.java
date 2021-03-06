package edu.gmu.hodum.sei.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import edu.gmu.hodum.sei.ui.LocationHolder;
import edu.gmu.hodum.sei.ui.MyApplication;
import edu.gmu.hodum.sei.util.Constants;

import android.app.Application;
import android.content.Context;
import android.location.Location;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.MulticastLock;
import android.util.Log;
import android.widget.Toast;

public class BroadcastNetworkManager implements NetworkManager, Sender {

	private Vector<Receiver> receivers = new Vector<Receiver>();
	private DatagramSocket datagramSocket;
	private final static int senderPort = 8882;
	private final static int receiverPort = 8881;
	
	private WifiManager wifi;
	private Context ctxt;
	private String macAddress;
	private long counter = 0;
	private SelectorThread receiverThread = null;
	private static BroadcastNetworkManager pInstance = null;
	private MulticastLock lock = null;
	private boolean networkStarted = false;
	private MyApplication locationHolder;
	private Map<String,HashSet<Long>> uniqueIDs = new HashMap<String, HashSet<Long> >();
	public static String uniqueID;

	public static BroadcastNetworkManager instance(Context ctxt, MyApplication locationHolder)
	{
		if(pInstance == null)
		{
			pInstance = new BroadcastNetworkManager(ctxt, locationHolder);
		}
		return pInstance;
	}

	private BroadcastNetworkManager(Context contxt, MyApplication locationHolder){
		this.ctxt = contxt;
		try{
			datagramSocket = new DatagramSocket(senderPort);
		}catch(Exception e)
		{
			e.printStackTrace();
		}
		this.locationHolder = locationHolder;
		// Setup WiFi
		wifi = (WifiManager) ctxt.getSystemService(Context.WIFI_SERVICE);
		lock = wifi.createMulticastLock("testing");
		lock.acquire();
		// Get WiFi status
		macAddress = wifi.getConnectionInfo().getMacAddress();
		if(macAddress == null)
		{
			macAddress = uniqueID;
		}
	}


	public void shuttingDown()
	{
		this.receiverThread.shuttingDown();
	}
	
	@Override
	public void sendMessage(Location center, double radius, byte[] buff)
	{
		checkIfIDsent(macAddress,counter);
		sendMessage(center, radius, macAddress, counter, buff);
		counter++;
	}


	private void sendMessage(Location center, double radius,String uniqueId, long counter, byte[] buff)
	{

		
		Location myLoc = locationHolder.getCurrentLocation();
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);
		try{
			dos.writeDouble(myLoc.getLatitude());
			dos.writeDouble(myLoc.getLongitude());

			encoodeUniqueID(dos, uniqueId, counter);
			dos.writeShort(Constants.ROUND_REGION_ADDRESS);
			dos.writeDouble(center.getLatitude());
			dos.writeDouble(center.getLongitude());
			dos.writeDouble(radius);

			dos.writeInt(buff.length);
			dos.write(buff);
			dos.flush();
			dos.close();
		}
		catch(IOException ex)
		{

		}

		try {
			sendPacket(baos.toByteArray());
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (DataExceedsMaxSizeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private void encoodeUniqueID(DataOutputStream dos, String uniqueID, long count) throws IOException
	{
		uniqueID = (uniqueID ==null ? "foo":uniqueID);
		dos.writeInt(uniqueID.length());
		dos.writeChars(uniqueID);
		dos.writeLong(count);
	}

	private boolean checkIfIDsent(String uniqueID, long id)
	{
		boolean ret = false;
		HashSet<Long> ids = uniqueIDs.get(uniqueID);
		if(ids != null)
		{
			if(!ids.add(new Long(id)))
			{
				ret = true;
			}
		}
		else
		{
			ids = new HashSet<Long>();
			ids.add(new Long(id));
			uniqueIDs.put(uniqueID, ids);
		}
		return ret;
	}

	@Override
	public boolean registerReceiver(Receiver receiver) {
		return receivers.add(receiver);
	}

	@Override
	public boolean removeReceiver(Receiver receiver) {
		return receivers.remove(receiver);

	}

	@Override
	public void beginReceivingPackets() {
		if(!networkStarted)
		{
			// TODO Auto-generated method stub
			// In this method, spawn thread and begin processing the network. 
			receiverThread = new SelectorThread();
			receiverThread.start();
			networkStarted = true;
		}
	}


	/**
	 * Sends data using the UDP protocol to a specific receiver
	 * @param destinationNodeID indicates the ID of the receiving node. Should be a positive integer.
	 * @param data is the message which is to be sent. 
	 * @throws IOException 
	 * @throws SizeLimitExceededException is thrown if the length of the data to be sent exceeds the limit
	 */
	private boolean sendPacket(byte[] data) throws IOException, DataExceedsMaxSizeException{
		if(data.length <= Constants.MAX_PACKAGE_SIZE){

			
			InetAddress IPAddress = InetAddress.getByName(Constants.broadcastAddress);
			//do we have a packet to be broadcasted?
			DatagramPacket sendPacket;

			//datagramSocket.setBroadcast(true);
			sendPacket = new DatagramPacket(data, data.length, IPAddress, receiverPort);

			//Toast.makeText(ctxt, "Sent Packet",  Toast.LENGTH_LONG);
			datagramSocket.send(sendPacket);

			return true;
		} else {
			throw new DataExceedsMaxSizeException();
		}
	}

	public void closeSoket(){
		datagramSocket.close();
	}

	protected void processPacket(ByteBuffer buf)
	{
		ByteArrayInputStream bais = new ByteArrayInputStream(buf.array());
		DataInputStream dis = new DataInputStream(bais);
		try{
			Location sourceLoc = new Location ("Other");
			sourceLoc.setLatitude(dis.readDouble());
			sourceLoc.setLongitude(dis.readDouble());

			int macAddressLength = dis.readInt();
			String receivedMacAddress = new String();
			for(int i=0;i<macAddressLength;i++)
			{
				receivedMacAddress+= dis.readChar();
			}
			long packetUniqueID = dis.readLong();
			//Check if I hav seen this, if so, just return
			if(checkIfIDsent(receivedMacAddress,packetUniqueID))
			{
				return;
			}
			Location myLoc = locationHolder.getCurrentLocation();
			short type = dis.readShort();
			if(type == Constants.ROUND_REGION_ADDRESS)
			{
				Location center = new Location("Other");
				center.setLatitude(dis.readDouble());
				center.setLongitude(dis.readDouble());
				double radius = dis.readDouble();
				int lngth = dis.readInt();
				byte[] buff = new byte[lngth];
				dis.readFully(buff);
				if(myLoc.distanceTo(center)<radius || radius == -1.0)
				{
					for(Receiver receiver:receivers)
					{
						receiver.receiveMessage(center, radius, sourceLoc, buff);
					}
				}
				//####DEBUG ONLY!!!
				else
				{
					for(Receiver receiver:receivers)
					{
						receiver.debugMessage(center, radius, sourceLoc, buff);
					}
				}
				//resend message
				//need to keep track of the unique ID so I don;t keep resending message
				BroadcastNetworkManager.this.sendMessage(center, radius, receivedMacAddress, packetUniqueID, buff);
			}

		}
		catch(IOException e)
		{

		}
	}

	private class SelectorThread extends Thread {

		private DatagramChannel channel;
		private boolean notShuttingDown = true;

		SelectorThread() {

			try {
				
				channel = DatagramChannel.open();

				channel.socket().bind(new InetSocketAddress(8881));
				channel.socket().setSoTimeout(1000);

			} catch (IOException e) {
				// TODO Auto-generated catch block
				
				e.printStackTrace();
			}
		}

		public void run() {
			while(notShuttingDown)
			{
				ByteBuffer buf = ByteBuffer.allocate(5000);
				buf.clear();
				try {
					SocketAddress retAddr = channel.receive(buf);
					if(retAddr != null)
					{
						BroadcastNetworkManager.this.processPacket(buf);
					}

				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}

		public void shuttingDown()
		{
			notShuttingDown = false;
		}
	}

	@Override
	public Sender getSender() {

		return this;
	}

}
