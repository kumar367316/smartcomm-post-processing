package com.htc.pclconverter.scheduler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.azure.storage.blob.BlobClientBuilder;
import com.htc.pclconverter.exception.ResourceNotFoundException;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.CloudBlob;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlobDirectory;
import com.microsoft.azure.storage.blob.CloudBlockBlob;
import com.microsoft.azure.storage.blob.ListBlobItem;

@Component
public class PostProcessingScheduler {

	Logger logger = LoggerFactory.getLogger(PostProcessingScheduler.class);

	@Autowired
	BlobClientBuilder client;

	@Value("${blob.connection-string}")
	private String storageConnectionString;

	@Value("${blob.dest.connection-string}")
	private String destStorageConnectionString;

	@Value("${blob.connection.accontName.accountKey}")
	private String connectionNameKey;

	@Value("${blob.container.name}")
	private String containerName;

	@Value("${blob.backup.container.name}")
	private String backupContainerName;

	@Value("${file.root.location}")
	private String fileRootLocation;

	@Value("${file.output.location}")
	private String fileOutputLocation;

	@Value("${state.document.type}")
	private String dcumentStateType;

	@Value("${state.document.type.NA}")
	private String invalidDocumentType;

	@Value("${notary.type.NA}")
	private String notaryType;

	@Value("${xml.type}")
	private String xmlType;

	@Value("${pdf.type}")
	private String pdfType;

	@Value("${page.type}")
	private String pageType;

	@Value("${banner.type}")
	private String bannerType;

	@Value("${sheet.type}")
	private String sheetType;

	@Scheduled(fixedDelay = 40000)
	public String smartCommPostProcessing() {
		String result = "smart comm post processing successfully";
		try {

			CloudBlobContainer container = this.containerInfo();

			DateTimeFormatter dateTimeFormat = DateTimeFormatter.ofPattern("YYYY-MM-dd");
			LocalDate localDate = LocalDate.now();
			String currentDate = dateTimeFormat.format(localDate);

			CloudBlobDirectory metaDir = container.getDirectoryReference("transit");
			CloudBlobDirectory transitPrintSubDirectory = metaDir.getDirectoryReference(currentDate + "-print");

			File targetFile = new File(fileRootLocation);
			if (!targetFile.exists()) {
				targetFile.mkdir();
			}

			CloudBlobDirectory printDirectory = container.getDirectoryReference("print");
			Iterable<ListBlobItem> listBlobs = printDirectory.listBlobs();
			//copy all file from print folder to transit folder
			if (!listBlobs.iterator().hasNext())
				throw new ResourceNotFoundException("file not found", HttpStatus.NOT_FOUND.value());
			for (ListBlobItem blob : listBlobs) {
				String[] blobDetails = blob.getUri().toString().split("/");
				String actualFileName = blobDetails[5].replace("%20", " ");
				File updateFile = new File(targetFile.getAbsolutePath() + "\\" + actualFileName);

				CloudBlob cloudBlob = (CloudBlob) blob;
				cloudBlob.downloadToFile(updateFile.toString());

				CloudBlockBlob failedSubDirectoryBlob = transitPrintSubDirectory.getBlockBlobReference(actualFileName);
				FileInputStream fileInputStream = new FileInputStream(updateFile);
				failedSubDirectoryBlob.upload(fileInputStream, updateFile.length());
				fileInputStream.close();
				cloudBlob.deleteIfExists();
			}

			// process meta data & pdf document
			this.processMetaDataInputFile(container, currentDate);
		} catch (ResourceNotFoundException exception) {
			logger.info("file not found for processing");
			result = "file not found for processing";
		} catch (Exception exception) {
			logger.info("Exception:" + exception.getMessage());
			result = "error in copy zip file to ftp server successfully";
		}
		return result;
	}

	public CloudBlobContainer containerInfo() {
		CloudBlobContainer container = null;
		try {
			CloudStorageAccount account = CloudStorageAccount.parse(connectionNameKey);
			CloudBlobClient serviceClient = account.createCloudBlobClient();
			container = serviceClient.getContainerReference(containerName);
		} catch (Exception exception) {
			exception.printStackTrace();
		}
		return container;
	}

	public void processMetaDataInputFile(CloudBlobContainer container, String currentDate) throws StorageException {
		try {
			String pageCount = "";
			String actualFileName = "";
			File updateFile = null;
			ConcurrentHashMap<String, List<String>> map = new ConcurrentHashMap<String, List<String>>();

			CloudBlobDirectory metaDir = container.getDirectoryReference("transit");
			CloudBlobDirectory transitPrintSubDirectory = metaDir.getDirectoryReference(currentDate + "-print");

			CloudBlobDirectory processSubDirectory = metaDir.getDirectoryReference("processed");
			CloudBlobDirectory failedSubDirectory = metaDir.getDirectoryReference("failed");

			Iterable<ListBlobItem> blobs = transitPrintSubDirectory.listBlobs();
			File targetFile = new File(fileRootLocation);
			if (!targetFile.exists()) {
				targetFile.mkdir();
			}

			PDDocument pdfDocument = new PDDocument();
			for (ListBlobItem blob : blobs) {
				String[] blobDetails = blob.getUri().toString().split("/");
				actualFileName = blobDetails[blobDetails.length - 1].replace("%20", " ");
				updateFile = new File(fileRootLocation + actualFileName);
				String fileNameWithOutExt = this.fileNameWithoutExt(actualFileName);
				if (fileNameWithOutExt.contains(notaryType)) {
					CloudBlockBlob failedSubDirectoryBlob = failedSubDirectory.getBlockBlobReference(actualFileName);
					FileInputStream fileInputStream = new FileInputStream(updateFile);
					failedSubDirectoryBlob.upload(fileInputStream, updateFile.length());
					fileInputStream.close();
					pdfDocument.close();
					continue;
				}
				String fileExtension = FilenameUtils.getExtension(updateFile.toString());

				CloudBlockBlob cloudBlob = (CloudBlockBlob) blob;
				cloudBlob.downloadToFile(updateFile.toString());

				if (fileExtension.equals(xmlType)) {
					//sheet count logic
					map = this.sheetCountDetails(actualFileName, map, updateFile, pdfDocument, failedSubDirectory);
				} else if (fileExtension.equals(pdfType)) {
					//PDF,multipage and SelfAddressed operation logic
					pdfDocument = PDDocument.load(new File(updateFile.toString()));
					pageCount = String.valueOf(pdfDocument.getPages().getCount());
					String fileNameList[] = updateFile.toString().split("_");
					String fileName = this.fileNameWithoutExt(fileNameList[fileNameList.length - 1]);
					if (dcumentStateType.contains(fileName)) {
						String[] actualFileNameList = updateFile.getName().split("_");
						pageCount = actualFileNameList[actualFileNameList.length - 1].substring(0,
								actualFileNameList[actualFileNameList.length - 1].lastIndexOf("."));
					} else if (!dcumentStateType.contains(fileNameList[fileNameList.length - 1])
							&& !updateFile.toString().contains(pageType)
							&& !updateFile.toString().contains(bannerType)) {
						CloudBlockBlob failedSubDirectoryBlob = failedSubDirectory
								.getBlockBlobReference(actualFileName);
						FileInputStream fileInputStream = new FileInputStream(updateFile);
						failedSubDirectoryBlob.upload(fileInputStream, updateFile.length());
						fileInputStream.close();
						pdfDocument.close();
						continue;
					}
					if (updateFile.toString().contains(bannerType)) {
						String[] actualFileNameList = updateFile.getName().split(bannerType);
						pageCount = actualFileNameList[actualFileNameList.length - 1].substring(0,
								actualFileNameList[actualFileNameList.length - 1].lastIndexOf("."));
						if (pageCount.matches(".*[0-9].*")) {
							pageCount = pageCount.replaceAll("[^\\d]", " ");
							pageCount = pageCount.trim();
							pageCount = pageCount.replaceAll(" +", " ");
						} else {
							String BannerPageNames[] = pageCount.split(bannerType);
							pageCount = BannerPageNames[0];
						}
					}
					if (map.containsKey(pageCount)) {
						List<String> existingFileNameList = new ArrayList<String>();
						existingFileNameList = map.get(pageCount);
						existingFileNameList.add(updateFile.getName());
						map.put(pageCount, existingFileNameList);
					} else {
						List<String> existingFileNameList = new ArrayList<String>();
						existingFileNameList.add(updateFile.getName());
						map.put(pageCount, existingFileNameList);
					}
				}
				pdfDocument.close();
			}
			//post processing PDF to PCL conversion
			this.convertPDFToPCL(updateFile, actualFileName, map, processSubDirectory);
		} catch (Exception exception) {
			logger.info("Exception found:" + exception.getMessage());
		}
	}

	public ConcurrentHashMap<String, List<String>> sheetCountDetails(String actualFileName,
			ConcurrentHashMap<String, List<String>> map, File updateFile, PDDocument pdfDocument,
			CloudBlobDirectory failedSubDirectory) {
		try {
			String sheetNumber = "";
			String xmlMetaDataFile = "";
			if (actualFileName.contains(pageType)) {
				xmlMetaDataFile = actualFileName;
				xmlMetaDataFile = FilenameUtils.getExtension(xmlMetaDataFile);
				DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
				DocumentBuilder builder = factory.newDocumentBuilder();

				Document document = builder.parse(updateFile);
				document.getDocumentElement().normalize();
				Element root = document.getDocumentElement();
				sheetNumber = root.getAttribute(sheetType);
				if (map.containsKey(sheetNumber)) {
					List<String> existingFileNameList = new ArrayList<String>();
					existingFileNameList = map.get(sheetNumber);
					existingFileNameList.add("");
					map.put(sheetNumber, existingFileNameList);
				} else {
					List<String> existingFileNameList = new ArrayList<String>();
					existingFileNameList.add("");
					map.put(sheetNumber, existingFileNameList);
				}
			} else {
				logger.info("state level xml is not require for process");
			}
		} catch (Exception exception) {
			logger.info("Exception :" + exception.getMessage());
		}
		return map;
	}

	public void convertPDFToPCL(File updateFile, String actualFileName, ConcurrentHashMap<String, List<String>> map,
			CloudBlobDirectory processSubDirectory) {
		ConcurrentHashMap<String, List<String>> BannerPageSortMap = new ConcurrentHashMap<String, List<String>>();

		try {
			map.forEach((key, value) -> {
				if (value.contains(""))
					value.removeAll(Arrays.asList("", null));
				Collections.sort(value);
				CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<String>();
				for (String temp : value) {
					if (temp.contains("Banner"))
						list.add(0, temp);
					else
						list.add(temp);
				}
				BannerPageSortMap.put(key, list);

			});

			BannerPageSortMap.forEach((key, fileNames) -> {
				PDFMergerUtility PDFmerger = new PDFMergerUtility();
				for (String sortPdfFile : fileNames) {
					try {
						PDFmerger.addSource(fileRootLocation + "\\" + sortPdfFile);
					} catch (FileNotFoundException e) {
						e.printStackTrace();
					}
				}
				try {
					String fileNumber = "";
					String fileNoExt = fileNames.get(fileNames.size() - 1).replaceFirst("[.][^.]+$", "");
					if (fileNoExt.length() >= 24)
						fileNumber = fileNoExt.substring(14, 24);
					else
						fileNumber = fileNoExt;
					PDFmerger.setDestinationFileName(fileRootLocation + "\\" + fileNumber + ".pdf");
					PDFmerger.mergeDocuments();
					File pdfMergePclFile = new File(fileOutputLocation);
					if (!pdfMergePclFile.exists()) {
						pdfMergePclFile.mkdir();
					}
					String outputFile = fileOutputLocation + fileNumber + ".pcl";

					pdfMergePclFile = new File(outputFile);
					PDDocument document = new PDDocument();
					document.save(outputFile);

					pdfMergePclFile.getAbsolutePath();
					File updatePCLFile = new File(outputFile);
					CloudBlockBlob processSubDirectoryBlob = processSubDirectory
							.getBlockBlobReference(fileNumber + ".pcl");
					FileInputStream fileInputStream = new FileInputStream(updatePCLFile);
					processSubDirectoryBlob.upload(fileInputStream, updatePCLFile.length());

					fileInputStream.close();
					document.close();
				} catch (Exception exception) {
					logger.info("Exception:" + exception.getMessage());
				}
			});
			File targetFile = new File(fileRootLocation);
			FileUtils.deleteDirectory(targetFile);
		} catch (Exception exception) {
			logger.info("Exception:" + exception.getMessage());
		}
	}

	public String fileNameWithoutExt(String fileName) {
		return FilenameUtils.removeExtension(fileName);
	}

}
