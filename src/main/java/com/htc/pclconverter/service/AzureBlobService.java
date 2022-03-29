package com.htc.pclconverter.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.azure.storage.blob.BlobClientBuilder;
import com.htc.pclconverter.exception.ResourceNotFoundException;

/**
 * @author kumar.charanswain
 *
 */

@Service
public class AzureBlobService {

	@Autowired
	BlobClientBuilder client;

	@Value("${blob.connection-string}")
	private String storageConnectionString;

	@Value("${blob.dest.connection-string}")
	private String destStorageConnectionString;

	@Value("${blob.connection.accontName.accountKey}")
	private String connectionNameKey;
	
	public String upload(MultipartFile[] files) {
		String result = "successfully upload document";
		if (files != null && files.length > 0) {
			try {
				for (MultipartFile file : files) {
					String fileName = file.getOriginalFilename();
					client.blobName(fileName).buildClient().upload(file.getInputStream(), file.getSize());
				}

			} catch (Exception exception) {
				if (exception.getMessage().contains("BlobAlreadyExists"))
					result = "The specified document already exists";
				else
					result = "Error in upload document";
				System.out.println("Exception:" + exception.getMessage());
			}
		} else {
			throw new ResourceNotFoundException("file is empty:Please add file for process", HttpStatus.NOT_FOUND.value());
		}
		return result;
	}
}