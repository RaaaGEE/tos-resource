package ru.rage.tos.resource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * @author PointerRage
 *
 */
public class Unpacker {
	private static File out = new File("./out");
	private static File in = new File("./input");
	private static ThreadPoolExecutor exec;
	private static boolean stacktrace = false;
	public static void main(String...args) throws Throwable {
		{
			int cpu = Math.max(Runtime.getRuntime().availableProcessors() / 2, 1);
			exec = (ThreadPoolExecutor) Executors.newFixedThreadPool(cpu);
			System.out.printf("Selected %d threads\r\n", cpu);
		}
		
		for(int i = 0; i < args.length; i++) {
			if(args[i].equals("-output") || args[i].equals("-out"))
				out = new File(args[++i]);
			else if(args[i].equals("-input") || args[i].equals("-in"))
				in = new File(args[++i]);
			else if(args[i].equals("-stacktrace"))
				stacktrace = true;
		}
		
		out.mkdirs();
		
		long allStartTime = System.currentTimeMillis();
		if(in.isFile())
			unpack(in);
		else
			for(File f : in.listFiles(file -> file.isFile() && file.exists() && file.getName().endsWith(".ipf")))
				unpack(f);
		exec.shutdown();
		long t = System.currentTimeMillis() - allStartTime;
		System.out.printf("Unpack all time: %d (%d s)\r\n", t, TimeUnit.MILLISECONDS.toSeconds(t));
	}
	
	private static void unpack(File f) throws Throwable {
		File archOut = null;
		long unpackStartTime = System.currentTimeMillis();
		short fcount;
		try(RandomAccessFile raf = new RandomAccessFile(f, "r"); FileChannel ch = raf.getChannel()) {
			byte[] tbuffer;
			
			System.out.printf("map file ro 0->%d", ch.size());
			MappedByteBuffer buffer = ch.map(MapMode.READ_ONLY, 0, ch.size());
			buffer.order(ByteOrder.LITTLE_ENDIAN);
			
			buffer.position(buffer.limit() - 8);
			IPFFile ipf = new IPFFile();
			ipf.setSubversion(buffer.getInt());
			ipf.setVersion(buffer.getInt());
			ipf.save(createFolderStruct(archOut, "version"));
			
			int header = buffer.limit() - Element.getTail().length - 4;
			System.out.printf(" ... jump to %d", header);
			buffer.position(header);
			int jmp = buffer.getInt();
			System.out.printf(" ... jump to %d\r\n", jmp);
			buffer.position(jmp);
			
			fcount = buffer.getShort(); //2
			System.out.printf("Files: %d\r\n", fcount);
			
			int ftableOffset = buffer.getInt(); //6
			System.out.printf("File table offset: %d\r\n", ftableOffset);
			
			short flag = buffer.getShort(); //8
			System.out.printf("GPBF: %d\r\n", flag);
			
			buffer.position(ftableOffset);
			
			for(int i = 0; i < fcount; i++) {
				short nameSize = buffer.getShort();
				int crc = buffer.getInt();
				int compressedSize = buffer.getInt();
				int originalSize = buffer.getInt();
				int fileOffset = buffer.getInt();
				
				short archiveSize = buffer.getShort();
				if(archOut == null) {
					String archive = null;
					tbuffer = new byte[archiveSize];
					buffer.get(tbuffer);
					archive = new String(tbuffer);
					System.out.printf("Archive (%d): %s\r\n", archiveSize, archive);
					archOut = new File(out, archive);
					archOut.mkdirs();
				} else
					buffer.position(buffer.position() + archiveSize);
				
				String fname;
				tbuffer = new byte[nameSize];
				buffer.get(tbuffer);
				fname = new String(tbuffer);
				
				File file = createFolderStruct(archOut, fname);
				
				final Element element = new Element();
				element.setFile(file);
				element.setName(fname);
				element.setCrc(crc);
				element.setCompressedSize(compressedSize);
				element.setOriginalSize(originalSize);
				element.setFileOffset(fileOffset);
				element.setArchive(f.getName());
				element.setArchiveFile(f);
				
				process(element);
				//exec.execute(() -> process(element));
			}
		}
		
		for(long csleep = 0;; csleep += 500) {
			if(exec.getQueue().isEmpty())
				break;
			if(csleep % 5000 == 0)
				System.out.printf("Extracted: %d/%d\r\n", fcount - exec.getQueue().size() - exec.getActiveCount() + 1, fcount);
			Thread.sleep(500);
		}
		exec.purge();
		Runtime.getRuntime().gc();
		
		System.out.printf("Unpack file time: %d\r\n\r\n", System.currentTimeMillis() - unpackStartTime);
	}
	
	private static void process(Element element) {
		try(RandomAccessFile r = new RandomAccessFile(element.getArchiveFile(), "r"); FileChannel fc = r.getChannel()) {
			MappedByteBuffer mbuffer = fc.map(MapMode.READ_ONLY, 0, fc.size());
			mbuffer.position(element.getFileOffset());
			element.setData(new byte[element.getCompressedSize()]);
			mbuffer.get(element.getData());
		} catch(IOException e) {
			System.out.printf("Failed read %s from archive %s\r\n", element.getFile().toString(), element.getArchive());
			if(stacktrace) {
				e.printStackTrace();
			}
			return;
		}
		
		if(element.getCompressedSize() != element.getOriginalSize()) {
			element.setData(decompress(element.getData()));
		}
		
		try {
			Files.write(element.getFile().toPath(), element.getData(), StandardOpenOption.CREATE);
		} catch (Exception e) {
			System.out.printf("Failed write %s\r\n", element.getFile().toString());
			if(stacktrace)
				e.printStackTrace();
		}
	}
	
	private static byte[] decompress(byte[] data) {
		Inflater inflater = new Inflater(true);
		try(InflaterInputStream is = new InflaterInputStream(new ByteArrayInputStream(data), inflater)) {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			while(is.available() > 0) {
				int b = is.read();
				if(is.available() > 0) //ололо костыль
					baos.write(b);
			}
			return baos.toByteArray();
		} catch(IOException e) {
			e.printStackTrace();
		} finally {
			inflater.end();
		} //never happen
		return null;
	}
	
	private static File createFolderStruct(File parent, String path) {
		String[] folders = path.split("/");
		if(folders.length < 1)
			return new File(parent, path);
		for(int i = 0; i < folders.length - 1; i++) {
			parent = new File(parent, folders[i]);
			parent.mkdirs();
		}
		return new File(parent, folders[folders.length-1]);
	}
}
