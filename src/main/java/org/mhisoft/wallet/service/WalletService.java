package org.mhisoft.wallet.service;

import java.io.File;
import java.io.IOException;

import org.mhisoft.common.util.security.HashingUtils;
import org.mhisoft.common.util.security.PBEEncryptor;
import org.mhisoft.wallet.model.FileAccessEntry;
import org.mhisoft.wallet.model.FileAccessFlag;
import org.mhisoft.wallet.model.FileAccessTable;
import org.mhisoft.wallet.model.ItemType;
import org.mhisoft.wallet.model.PassCombinationVO;
import org.mhisoft.wallet.model.WalletItem;
import org.mhisoft.wallet.model.WalletModel;
import org.mhisoft.wallet.view.DialogUtils;

/**
 * Description:
 *
 * @author Tony Xue
 * @since Apr, 2016
 */
public class WalletService {

	AttachmentService attachmentService = ServiceRegistry.instance.getService(BeanType.singleton, AttachmentService.class);


	public StoreVO readFromFile(final String filename, final PBEEncryptor encryptor) {
		FileContentHeader header = readHeader(filename, true);
		DataService ds = DataServiceFactory.createDataService(header.getVersion());
		StoreVO ret =  ds.readFromFile(filename, encryptor);
		String attFileName = attachmentService.getAttachmentFileName(filename);
		FileAccessTable t = attachmentService.read(attFileName, encryptor);
		if (t!=null) {
			for (FileAccessEntry entry : t.getEntries()) {
				WalletItem item = ret.getWalletItem(entry.getGUID());
				if (item!=null) {
					item.setAttachmentEntry(entry);
					item.setNewAttachmentEntry(null);
				}
			}
			ret.setDeletedEntriesInStore(t.getDeletedEntries());
		}

		return ret;


	}


	/**
	 * Create the model by reading from the vault file.
	 * @param vaultFileName
	 * @param encryptor
	 * @return
	 */
	public WalletModel createModelByReadVaultFile(final String vaultFileName, final PBEEncryptor encryptor) {

		StoreVO vo = readFromFile(vaultFileName, encryptor);

		WalletModel model = new WalletModel();
		model.setPassHash(vo.getHeader().getPassHash());
		model.setCombinationHash(vo.getHeader().getCombinationHash());
		model.setDataFileVersion(vo.getHeader().getVersion());

		model.setEncryptor(encryptor);
		model.setItemsFlatList(vo.getWalletItems());
		model.buildTreeFromFlatList();
		model.setVaultFileName(vaultFileName);
		return model;

	}


	/**
	 *
	 * @param filename main store filename.
	 * @param model the model to be saved.
	 * @param encryptor encrypor to use for write the new store.
	 */
	public void saveToFile(final String filename, final WalletModel model, final PBEEncryptor encryptor) {

		for (WalletItem item : model.getItemsFlatList()) {
			int k = item.getName().indexOf("(*)");
			if (k>0) {
				item.setName(item.getName().substring(0, k));
			}
		}


		//save with the latest version of data services.
		DataServiceFactory.createDataService().saveToFile(filename, model, encryptor);

		//save attachments.
		AttachmentService attachmentService = ServiceRegistry.instance.getService(BeanType.singleton, AttachmentService.class);

		//upgrade the current store to the lates first.
		if (model.getCurrentDataFileVersion()!=WalletModel.LATEST_DATA_VERSION) {
			upgradeAttachmentStore(filename, model, encryptor);


		}
		else {
			//then save as regular.
			attachmentService.saveAttachments(attachmentService.getAttachmentFileName(filename), model, encryptor);
		}

	}

	/**
	 * Save the model to a new exported store.
	 * And if there are attachments on the item, we need to read the content out from the old attachment store and transfer to a new one.
	 * @param expVaultName
	 * @param expModel
	 * @param expEncryptor
	 */
	public void export(final String existingVaultFileName,
			final String expVaultName, final WalletModel expModel, final PBEEncryptor expEncryptor) {


		DataServiceFactory.createDataService().saveToFile(expVaultName, expModel, expEncryptor);

		//save attachments.
		AttachmentService attachmentService = ServiceRegistry.instance.getService(BeanType.singleton, AttachmentService.class);
		attachmentService.transferAttachmentStore(
				 attachmentService.getAttachmentFileName(existingVaultFileName)
				,attachmentService.getAttachmentFileName(expVaultName)
				,ServiceRegistry.instance.getWalletModel()
				,expModel, expEncryptor
				, false); //no change to original model

	}



	/**
	 *
	 * @param filename
	 * @param model
	 * @param newEnc This is the new encryptor with new pass
	 */
	public void saveToFileWithNewPassword(final String filename, final WalletModel model, final PBEEncryptor newEnc) {

		for (WalletItem item : model.getItemsFlatList()) {
			int k = item.getName().indexOf("(*)");
			if (k>0) {
				item.setName(item.getName().substring(0, k));
			}
		}


		DataServiceFactory.createDataService().saveToFile(filename, model, newEnc);

		//save attachments.
		AttachmentService attachmentService = ServiceRegistry.instance.getService(BeanType.singleton, AttachmentService.class);

		//transfer to the ne store with new password.
		String oldStoreName = attachmentService.getAttachmentFileName(filename);
		String newStoreName = oldStoreName + ".tmp";

		//the same one model, just that use the new encryptor for writing the new store.
		if (attachmentService.transferAttachmentStore( oldStoreName,  newStoreName  , model, model, newEnc, true)) {
			//now do the swap of the store to the new one.
			new File(oldStoreName).delete();
			File newFile = new File(newStoreName);
			newFile.renameTo(new File(oldStoreName));
		}

	}


	/**
	 *
	 * @param vaultFileName main store file name
	 * @param model current model
	 * @param encryptor encryptor use to write the new store.
	 */
	public void upgradeAttachmentStore(final String vaultFileName, final WalletModel model,final PBEEncryptor encryptor) {

		//save attachments.
		AttachmentService attachmentService = ServiceRegistry.instance.getService(BeanType.singleton, AttachmentService.class);

		//transfer to the the store
		String oldStoreName = attachmentService.getAttachmentFileName(vaultFileName);
		String newStoreName = oldStoreName + ".tmp";

		//the same one model, just that use the new encryptor for writing the new store.
		if (attachmentService.transferAttachmentStore( oldStoreName,  newStoreName  , model, model, encryptor, false)) {
			//now do the swap of the store to the new one.
			new File(oldStoreName).delete();
			File newFile = new File(newStoreName);
			newFile.renameTo(new File(oldStoreName));

			// reload entries into a model, the attment entry  pos points has changed.
			WalletModel  newModel =  model.clone();
			newModel.setDataFileVersion(WalletModel.LATEST_DATA_VERSION);

			//re read the new store into newModel
			attachmentService.reloadAttachments(vaultFileName, newModel );

			for (WalletItem walletItem : model.getItemsFlatList()) {
				if (walletItem.getAttachmentEntry()!=null) {
					if (walletItem.getAttachmentEntry().getAccessFlag()== FileAccessFlag.Delete) {
						//ignore the deleted attachments. they does not exist in the new store.
						walletItem.setAttachmentEntry(null);
						walletItem.setNewAttachmentEntry(null);
					}
					else if (walletItem.getAttachmentEntry().getAccessFlag()!= FileAccessFlag.Update) {
						//to be appended to new store.
						walletItem.getAttachmentEntry().setAccessFlag(FileAccessFlag.Create);
					}
					else {
						/*NONE -- transfered*/
						WalletItem newModelItem = walletItem.findItemInModel(newModel);
						if (newModelItem != null && newModelItem.getAttachmentEntry() != null)
							//transfered to the new store already. no more action. so clear it out in the model
							walletItem.setAttachmentEntry(null);
							walletItem.setNewAttachmentEntry(null);
					}
				}
			}


			//NONE items were transered.
			//DELETE items is set to null , ignored.
			//UPDATE --> create
			attachmentService.appendAttachmentStore(vaultFileName, model, encryptor);


		}
		else {

			/* nothing transferred. such as all attachments are marked as deleted. */

			for (WalletItem walletItem : model.getItemsFlatList()) {
				if (walletItem.getAttachmentEntry() != null) {
					if (walletItem.getAttachmentEntry().getAccessFlag() == FileAccessFlag.Delete) {
						//ignore the deleted attachments. they does not exist in the new store.
						walletItem.setAttachmentEntry(null);
						walletItem.setNewAttachmentEntry(null);
					}
				}
			}


			//no transfer happened.
			//no upgrade , creating of new store with latest version happened.
			attachmentService.newAttachmentStore(vaultFileName, model, encryptor);
		}


		//re read the new store into newModel
		attachmentService.reloadAttachments(vaultFileName, model );

	}




	private FileContentHeader readVersion(DataService ds, final String filename) {
		try {
			FileContentHeader header = ds.readHeader(filename, true);
			int v = header.getVersion();
			return header;
		} catch (IOException e) {
//			if (DialogUtils.getInstance() != null)
//				DialogUtils.getInstance().error("Error occurred", "Can't read file " + filename);
			e.printStackTrace();
		}
		return null;
	}

	public FileContentHeader readHeader(final String filename, boolean closeAfterRead) {
		DataService dataServicev10 = DataServiceFactory.createDataService(10);
		DataService dataServicev12 = DataServiceFactory.createDataService(12);
		DataService dataServicev13 = DataServiceFactory.createDataService(13);

		int v;
		FileContentHeader header = null;

		header = readVersion(dataServicev13, filename);
		if (header == null) {
			header = readVersion(dataServicev12, filename);
			if (header == null)
				header = readVersion(dataServicev10, filename);
		}

		if (header==null) {
			if (DialogUtils.getInstance() != null)
				DialogUtils.getInstance().error("Error occurred", "Can't read file " + filename);
			throw new RuntimeException("Can't read file  header" + filename) ;
		}

//		if (SystemSettings.isDevMode && DialogUtils.getInstance() != null)
//			DialogUtils.getInstance().info("file version:" + header.getVersion());
		return header;


	}




	/**
	 * Export the sourceItem to a new vault for transportation.
	 * @param sourceItem the source item to be exported.
	 * @param exportVaultPassVO the passwords for the new vault.
	 * @param exportVaultFilename The new vault name.
	 */
	public void exportItem(final WalletItem sourceItem
	      ,final PassCombinationVO exportVaultPassVO
			, final String exportVaultFilename ) {
		try {


			if (sourceItem.getType()==ItemType.category) {
				//not suporoted. now.
				DialogUtils.getInstance().warn("Error", "Category is not supported yet. Select an item instead.");
			}
			else {
				//get its parent.


				WalletModel expModel = new WalletModel();
				String hash2 = HashingUtils.createHash(exportVaultPassVO.getPass());
				String combinationHash2 = HashingUtils.createHash(exportVaultPassVO.getCombination());
				expModel.setHash(hash2, combinationHash2);
				expModel.initEncryptor(exportVaultPassVO);

				WalletItem newParent=null;
				WalletItem newItem = sourceItem.clone();
				if (sourceItem.getParent()!=null) {
					newParent =sourceItem.getParent().clone();
					newParent.addChild(newItem);
				}


				WalletItem root = new WalletItem(ItemType.category, "export");
				expModel.getItemsFlatList().add(root);
				if (newParent!=null)
					expModel.getItemsFlatList().add(newParent);
				expModel.getItemsFlatList().add(newItem);

				//save to the export vault.
				String vaultFileName = ServiceRegistry.instance.getWalletModel().getVaultFileName();
				export(vaultFileName, exportVaultFilename, expModel, expModel.getEncryptor());

				try {
					DialogUtils.getInstance().info("The item " + sourceItem.getName() +" has been successfully exported to vault:" + exportVaultFilename);
				} catch (Exception e) {
					e.printStackTrace();
				}


			}


		} catch (HashingUtils.CannotPerformOperationException e) {
			e.printStackTrace();
			DialogUtils.getInstance().error("An error occurred while trying to export the entry", e.getMessage());
		}

	}


}
