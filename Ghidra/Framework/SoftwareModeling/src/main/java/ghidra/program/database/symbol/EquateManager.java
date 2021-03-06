/* ###
 * IP: GHIDRA
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
package ghidra.program.database.symbol;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import db.*;
import db.util.ErrorHandler;
import ghidra.program.database.*;
import ghidra.program.database.map.AddressKeyAddressIterator;
import ghidra.program.database.map.AddressMap;
import ghidra.program.model.address.*;
import ghidra.program.model.data.DataTypeManager;
import ghidra.program.model.data.Enum;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.Equate;
import ghidra.program.model.symbol.EquateTable;
import ghidra.program.util.ChangeManager;
import ghidra.program.util.EquateInfo;
import ghidra.util.*;
import ghidra.util.exception.*;
import ghidra.util.task.TaskMonitor;

/**
 * Implementation for the Equate Table.
 *
 *
 */
public class EquateManager implements EquateTable, ErrorHandler, ManagerDB {

	private AddressMap addrMap;
	private DBObjectCache<EquateRefDB> refCache;
	private DBObjectCache<EquateDB> equateCache;
	private EquateDBAdapter equateAdapter;
	private EquateRefDBAdapter refAdapter;
	private ProgramDB program;
	private Lock lock;
	public static final String DATATYPE_TAG = "dtID";
	public static final String ERROR_TAG = "<BAD EQUATE>";
	public static final String FORMAT_DELIMITER = ":";

	/**
	 * Constructor
	 * @param handle database handle
	 * @param addrMap map that converts addresses to longs and longs to
	 * addresses
	 * @param openMode one of ProgramDB.CREATE, UPDATE, UPGRADE, or READ_ONLY
	 * @param lock the program synchronization lock
	 * @param monitor the progress monitor used when upgrading.
	 * @throws VersionException if the database version doesn't match the current version.
	 * @throws IOException if a database error occurs.
	 * @throws CancelledException if the user cancels the upgrade.
	 */
	public EquateManager(DBHandle handle, AddressMap addrMap, int openMode, Lock lock,
			TaskMonitor monitor) throws VersionException, IOException {

		this.addrMap = addrMap;
		this.lock = lock;
		initializeAdapters(handle, openMode, monitor);
		refCache = new DBObjectCache<>(100);
		equateCache = new DBObjectCache<>(100);
	}

	ProgramDB getProgram() {
		return program;
	}

	private void initializeAdapters(DBHandle handle, int openMode, TaskMonitor monitor)
			throws VersionException, IOException {
		VersionException versionExc = null;
		try {
			equateAdapter = EquateDBAdapter.getAdapter(handle, openMode, monitor);
		}
		catch (VersionException e) {
			versionExc = e.combine(versionExc);
		}
		try {
			refAdapter = EquateRefDBAdapter.getAdapter(handle, openMode, addrMap, monitor);
		}
		catch (VersionException e) {
			versionExc = e.combine(versionExc);
		}
		if (versionExc != null) {
			throw versionExc;
		}
	}

	/**
	 * @see ghidra.program.database.ManagerDB#setProgram(ghidra.program.database.ProgramDB)
	 */
	@Override
	public void setProgram(ProgramDB program) {
		this.program = program;
	}

	/**
	 * @see ghidra.program.database.ManagerDB#programReady(int, int, ghidra.util.task.TaskMonitor)
	 */
	@Override
	public void programReady(int openMode, int currentRevision, TaskMonitor monitor)
			throws IOException, CancelledException {
		// Nothing to do
	}

	/**
	 * @see db.util.ErrorHandler#dbError(java.io.IOException)
	 */
	@Override
	public void dbError(IOException e) {
		program.dbError(e);
	}

	@Override
	public void applyEnum(AddressSetView addresses, Enum enoom, TaskMonitor monitor,
			boolean shouldDoOnSubOps) throws CancelledException {

		if (addresses == null) {
			throw new IllegalArgumentException("Can't apply Enum over null addresses");
		}

		if (enoom == null) {
			throw new IllegalArgumentException("Data Type is null");
		}

		Consumer<Instruction> applyEquates = instruction -> {

			if (monitor.isCancelled()) {
				return;
			}

			for (int opIndex = 0; opIndex < instruction.getNumOperands(); opIndex++) {

				if (!shouldDoOnSubOps) {
					// Only apply equates to scalars that are not contained in sub operands.
					Scalar scalar = instruction.getScalar(opIndex);
					maybeCreateEquateOnScalar(enoom, instruction, opIndex, scalar);
				}
				else {
					// Apply equates to scalars in the sub operands as well.
					List<?> subOperands = instruction.getDefaultOperandRepresentationList(opIndex);
					for (Object subOp : subOperands) {
						maybeCreateEquateOnScalar(enoom, instruction, opIndex, subOp);
					}
				}
			}
		};

		Listing listing = program.getListing();
		InstructionIterator it = listing.getInstructions(addresses, true);
		Stream<Instruction> instructions = StreamSupport.stream(it.spliterator(), false);

		DataTypeManager dtm = program.getDataTypeManager();
		try {
			lock.acquire();
			int id = dtm.startTransaction("Apply Enum");
			instructions.forEach(applyEquates);
			dtm.endTransaction(id, true);
		}
		finally {
			lock.release();
		}
	}

	private void maybeCreateEquateOnScalar(Enum enoom, Instruction instruction, int opIndex,
			Object operandRepresentation) {

		if (!(operandRepresentation instanceof Scalar)) {
			return;
		}

		Scalar scalar = (Scalar) operandRepresentation;

		int enoomLength = enoom.getLength();
		boolean anyValuesMatch = Arrays.stream(enoom.getValues()).anyMatch(enumValue -> {
			return scalar.equals(new Scalar(enoomLength * 8, enumValue, scalar.isSigned()));
		});

		if (!anyValuesMatch) {
			return;
		}

		if (program.getDataManager().findDataTypeForID(enoom.getUniversalID()) == null) {
			int transactionID = program.startTransaction("Set Equate Dialog");
			try {
				enoom = (Enum) program.getDataManager().addDataType(enoom, null);
			}
			finally {
				program.endTransaction(transactionID, true);
			}
		}

		Address addr = instruction.getAddress();
		removeUnusedEquates(opIndex, scalar, addr);

		long value = scalar.getValue();
		String equateName = EquateManager.formatNameForEquate(enoom.getUniversalID(), value);
		Equate equate = getOrCreateEquate(equateName, value);
		equate.addReference(addr, opIndex);
	}

	private void removeUnusedEquates(int opIndex, Scalar scalar, Address addr) {
		Equate existingEquate = getEquate(addr, opIndex, scalar.getValue());
		if (existingEquate != null) {
			if (existingEquate.getReferenceCount() <= 1) {
				removeEquate(existingEquate.getName());
			}
		}
	}

	private Equate getOrCreateEquate(String name, long value) {
		Equate equate = getEquate(name);
		if (equate != null) {
			return equate;
		}

		try {
			equate = createEquate(name, value);
		}
		catch (DuplicateNameException | InvalidInputException e) {
			// These should not happen:
			// Duplicate will not happen since we checked for the existence first; Invalid 
			// can't happen since we built the name ourselves (we are assuming)
			Msg.error(this, "Unexpected error creating equate", e);  // just in case
		}
		return equate;
	}

	/**
	 * @see ghidra.program.model.symbol.EquateTable#createEquate(java.lang.String, long)
	 */
	@Override
	public Equate createEquate(String name, long value)
			throws DuplicateNameException, InvalidInputException {
		lock.acquire();
		try {
			if (equateAdapter.hasRecord(name)) {
				throw new DuplicateNameException(name + " already exists for an equate.");
			}
			validateName(name);
			Record record = equateAdapter.createEquate(name, value);
			EquateDB equate = new EquateDB(this, equateCache, record);
			program.setChanged(ChangeManager.DOCR_EQUATE_ADDED,
				new EquateInfo(name, value, null, 0, 0), null);
			return equate;

		}
		catch (IOException e) {
			program.dbError(e);
		}
		finally {
			lock.release();
		}
		return null;
	}

	/**
	 * @see ghidra.program.model.symbol.EquateTable#getEquate(ghidra.program.model.address.Address, int, long)
	 */
	@Override
	public Equate getEquate(Address reference, int opIndex, long scalarValue) {
		lock.acquire();
		try {
			long refAddr = addrMap.getKey(reference, false);
			if (refAddr == AddressMap.INVALID_ADDRESS_KEY) {
				return null;
			}
			long[] keys = refAdapter.getRecordKeysForAddr(refAddr);
			for (long key : keys) {
				EquateRefDB ref = getEquateRefDB(key);
				if (ref.getOpIndex() == opIndex) {
					EquateDB equate = getEquateDB(ref.getEquateID());
					if (equate.getValue() == scalarValue) {
						return equate;
					}
				}
			}
		}
		catch (IOException e) {
			program.dbError(e);
		}
		finally {
			lock.release();
		}
		return null;
	}

	/**
	 * @see ghidra.program.model.symbol.EquateTable#getEquates(ghidra.program.model.address.Address, int)
	 */
	@Override
	public List<Equate> getEquates(Address reference, int opIndex) {
		List<Equate> ret = new LinkedList<>();
		lock.acquire();
		try {
			long refAddr = addrMap.getKey(reference, false);
			if (refAddr == AddressMap.INVALID_ADDRESS_KEY) {
				return ret;
			}
			long[] keys = refAdapter.getRecordKeysForAddr(refAddr);
			for (long key : keys) {
				EquateRefDB ref = getEquateRefDB(key);
				if (ref.getOpIndex() == opIndex) {
					ret.add(getEquateDB(ref.getEquateID()));
				}
			}
		}
		catch (IOException e) {
			program.dbError(e);
		}
		finally {
			lock.release();
		}
		return ret;
	}

	/**
	 * @see ghidra.program.model.symbol.EquateTable#getEquates(ghidra.program.model.address.Address)
	 */
	@Override
	public List<Equate> getEquates(Address reference) {
		List<Equate> ret = new LinkedList<>();
		lock.acquire();
		try {
			long refAddr = addrMap.getKey(reference, false);
			if (refAddr == AddressMap.INVALID_ADDRESS_KEY) {
				return ret;
			}
			long[] keys = refAdapter.getRecordKeysForAddr(refAddr);
			for (long key : keys) {
				EquateRefDB ref = getEquateRefDB(key);
				ret.add(getEquateDB(ref.getEquateID()));
			}
		}
		catch (IOException e) {
			program.dbError(e);
		}
		finally {
			lock.release();
		}
		return ret;
	}

	/**
	 * @see ghidra.program.model.symbol.EquateTable#getEquate(java.lang.String)
	 */
	@Override
	public Equate getEquate(String name) {
		lock.acquire();
		try {
			long equateID = equateAdapter.getRecordKey(name);
			return getEquateDB(equateID);
		}
		catch (NotFoundException e) {
		}
		catch (IOException e) {
			program.dbError(e);
		}
		finally {
			lock.release();
		}
		return null;
	}

	/**
	 * @see ghidra.program.model.symbol.EquateTable#getEquateAddresses()
	 */
	@Override
	public AddressIterator getEquateAddresses() {

		try {
			return new AddressKeyAddressIterator(refAdapter.getIteratorForAddresses(), true,
				addrMap, this);
		}
		catch (IOException e) {
			program.dbError(e);
		}
		return new EmptyAddressIterator();
	}

	/**
	 * @see ghidra.program.model.symbol.EquateTable#getEquateAddresses(ghidra.program.model.address.Address)
	 */
	@Override
	public AddressIterator getEquateAddresses(Address startAddr) {
		try {
			return new AddressKeyAddressIterator(refAdapter.getIteratorForAddresses(startAddr),
				true, addrMap, this);
		}
		catch (IOException e) {
			program.dbError(e);
		}
		return new EmptyAddressIterator();
	}

	private AddressIterator getEquateAddresses(Address startAddr, Address endAddr) {
		try {
			return new AddressKeyAddressIterator(
				refAdapter.getIteratorForAddresses(startAddr, endAddr), true, addrMap, this);
		}
		catch (IOException e) {
			program.dbError(e);
		}
		return new EmptyAddressIterator();
	}

	/**
	 * @see ghidra.program.model.symbol.EquateTable#getEquateAddresses(ghidra.program.model.address.AddressSetView)
	 */
	@Override
	public AddressIterator getEquateAddresses(AddressSetView set) {
		try {
			return new AddressKeyAddressIterator(refAdapter.getIteratorForAddresses(set), true,
				addrMap, this);
		}
		catch (IOException e) {
			program.dbError(e);
		}
		return new EmptyAddressIterator();
	}

	/**
	 * @see ghidra.program.model.symbol.EquateTable#getEquates()
	 */
	@Override
	public Iterator<Equate> getEquates() {
		try {
			RecordIterator iter = equateAdapter.getRecords();
			return new EquateIterator(iter);
		}
		catch (IOException e) {
			program.dbError(e);
		}
		return new EquateIterator(null);
	}

	/**
	 * @see ghidra.program.model.symbol.EquateTable#getEquates(long)
	 */
	@Override
	public List<Equate> getEquates(long value) {
		ArrayList<Equate> list = new ArrayList<>();
		try {
			RecordIterator iter = equateAdapter.getRecords();
			while (iter.hasNext()) {
				Record rec = iter.next();
				EquateDB equateDB = getEquateDB(rec.getKey());
				if (equateDB.getValue() == value) {
					list.add(equateDB);
				}
			}
		}
		catch (IOException e) {
			program.dbError(e);
		}
		return list;
	}

	/**
	 * @see ghidra.program.database.ManagerDB#deleteAddressRange(ghidra.program.model.address.Address, ghidra.program.model.address.Address, ghidra.util.task.TaskMonitor)
	 */
	@Override
	public void deleteAddressRange(Address startAddr, Address endAddr, TaskMonitor monitor)
			throws CancelledException {
		lock.acquire();
		try {
			ArrayList<EquateRefDB> list = new ArrayList<>();
			AddressIterator iter = getEquateAddresses(startAddr, endAddr);
			while (iter.hasNext()) {
				if (monitor.isCancelled()) {
					throw new CancelledException();
				}
				Address addr = iter.next();
				long[] keys = refAdapter.getRecordKeysForAddr(addrMap.getKey(addr, false));
				for (long key : keys) {
					EquateRefDB ref = getEquateRefDB(key);
					list.add(ref);
				}
			}
			for (int i = 0; i < list.size(); i++) {
				if (monitor.isCancelled()) {
					throw new CancelledException();
				}
				EquateRefDB ref = list.get(i);
				EquateDB equateDB = getEquateDB(ref.getEquateID());

				removeRef(equateDB, ref);
				if (getReferenceCount(equateDB.getKey()) == 0) {
					removeEquate(equateDB);
				}
			}

		}
		catch (IOException e) {
			program.dbError(e);
		}
		finally {
			lock.release();
		}
	}

	/**
	 * @see ghidra.program.model.symbol.EquateTable#removeEquate(java.lang.String)
	 */
	@Override
	public boolean removeEquate(String name) {
		if (name == null) {
			return false;
		}
		lock.acquire();
		try {

			EquateDB equateDB = (EquateDB) getEquate(name);
			if (equateDB != null) {
				long equateID = equateDB.getKey();

				// remove the references that have this equateID
				removeReferences(equateID);
				removeEquate(equateDB);
				return true;
			}

		}
		catch (IOException e) {
			program.dbError(e);
		}
		finally {
			lock.release();
		}
		return false;
	}

	private void removeEquate(EquateDB equateDB) throws IOException {
		lock.acquire();
		try {
			String name = equateDB.getName();
			long equateID = equateDB.getKey();
			equateAdapter.removeRecord(equateID);
			equateCache.delete(equateID);
			// fire event: oldValue = equate name, newValue=null
			program.setChanged(ChangeManager.DOCR_EQUATE_REMOVED, name, null);
		}
		finally {
			lock.release();
		}
	}

	/**
	 * Return the address map.
	 */
	AddressMap getAddressMap() {
		return addrMap;
	}

	/**
	 * Get the database adapter for equate table.
	 */
	EquateDBAdapter getEquateDatabaseAdapter() {
		return equateAdapter;
	}

	/**
	 * Get the database adapter for the equate references table.
	 */
	EquateRefDBAdapter getRefDatabaseAdapter() {
		return refAdapter;
	}

	/**
	 * Add a reference for an equate at the given operand position.
	 */
	void addReference(long equateID, Address address, int opIndex, long dynamicHash)
			throws IOException {
		lock.acquire();
		try {
			EquateDB equateDB = getEquateDB(equateID);
			String name = equateDB.getName();
			long value = equateDB.getValue();

			long addr = addrMap.getKey(address, true);

			// first remove reference for address and opIndex
			long[] keys = refAdapter.getRecordKeysForAddr(addr);
			for (long key : keys) {
				EquateRefDB ref = getEquateRefDB(key);
				if (dynamicHash != 0) {
					if (ref.getDynamicHashValue() == dynamicHash) {
						removeRef(equateDB, ref);
					}
				}
				else if (ref.getDynamicHashValue() == 0 && ref.getOpIndex() == opIndex) {
					removeRef(equateDB, ref);
				}
			}
			Record record =
				refAdapter.createReference(addr, (short) opIndex, dynamicHash, equateID);
			new EquateRefDB(this, refCache, record);

			// fire event: oldValue=EquateInfo, newValue = null
			program.setChanged(ChangeManager.DOCR_EQUATE_REFERENCE_ADDED, address, address,
				new EquateInfo(name, value, address, opIndex, dynamicHash), null);
		}
		finally {
			lock.release();
		}
	}

	/**
	 * Get the references for the given equate ID.
	 */
	EquateRefDB[] getReferences(long equateID) throws IOException {
		long[] keys = refAdapter.getRecordKeysForEquateID(equateID);
		EquateRefDB[] refs = new EquateRefDB[keys.length];
		for (int i = 0; i < keys.length; i++) {
			refs[i] = getEquateRefDB(keys[i]);
		}
		return refs;
	}

	/**
	 * Get the number of references for the given equate ID.
	 */
	int getReferenceCount(long equateID) throws IOException {
		return getReferences(equateID).length;
	}

	/**
	 * Remove the reference.
	 * @param equateDB equate
	 * @param refAddr ref address to remove
	 * @param opIndex operand index
	 */
	void removeReference(EquateDB equateDB, Address refAddr, short opIndex) throws IOException {

		long[] keys = refAdapter.getRecordKeysForEquateID(equateDB.getKey());
		for (long key : keys) {
			EquateRefDB ref = getEquateRefDB(key);
			if (ref.getOpIndex() == opIndex && ref.getAddress().equals(refAddr)) {
				removeRef(equateDB, ref);
				break;
			}
		}
	}

	/**
	 * Remove the reference.
	 * @param equateDB equate
	 * @param dynamicHash hash value
	 * @param refAddr ref address to remove
	 * @param opIndex operand index
	 */
	void removeReference(EquateDB equateDB, long dynamicHash, Address refAddr) throws IOException {

		long[] keys = refAdapter.getRecordKeysForEquateID(equateDB.getKey());
		for (long key : keys) {
			EquateRefDB ref = getEquateRefDB(key);
			if (ref.getDynamicHashValue() == dynamicHash && ref.getAddress().equals(refAddr)) {
				removeRef(equateDB, ref);
				break;
			}
		}
	}

	/**
	 * Verify that the name is not null and not the empty string.
	 * @throws InvalidInputException if the name is null or is empty
	 */
	void validateName(String name) throws InvalidInputException {
		if (name == null) {
			throw new InvalidInputException("Name is null");
		}
		name = name.trim();
		if (name.length() == 0) {
			throw new InvalidInputException("Name is empty string.");
		}
	}

	/**
	 * Send notification that the equate name changed.
	 * @param oldName old name
	 * @param newName new name
	 */
	void equateNameChanged(String oldName, String newName) {
		program.setChanged(ChangeManager.DOCR_EQUATE_RENAMED, oldName, newName);
	}

	Record getEquateRecord(long equateID) {
		try {
			return equateAdapter.getRecord(equateID);
		}
		catch (IOException e) {
			dbError(e);
		}
		return null;
	}

	Record getEquateRefRecord(long refID) {
		try {
			return refAdapter.getRecord(refID);
		}
		catch (IOException e) {
			dbError(e);
		}
		return null;
	}

	private void removeRef(EquateDB equateDB, EquateRefDB ref) throws IOException {
		lock.acquire();
		try {
			long key = ref.getKey();
			refAdapter.removeRecord(key);
			refCache.delete(key);
			referenceRemoved(equateDB, ref.getAddress(), ref.getOpIndex(),
				ref.getDynamicHashValue());
		}
		finally {
			lock.release();
		}

	}

	private EquateDB getEquateDB(long equateID) throws IOException {
		lock.acquire();
		try {
			EquateDB equateDB = equateCache.get(equateID);
			if (equateDB == null) {
				Record record = equateAdapter.getRecord(equateID);
				if (record != null) {
					equateDB = new EquateDB(this, equateCache, record);
				}
			}
			return equateDB;
		}
		finally {
			lock.release();
		}
	}

	private EquateRefDB getEquateRefDB(long key) throws IOException {
		lock.acquire();
		try {
			EquateRefDB ref = refCache.get(key);
			if (ref == null) {
				Record record = refAdapter.getRecord(key);
				ref = new EquateRefDB(this, refCache, record);
			}
			return ref;
		}
		finally {
			lock.release();
		}
	}

	private void removeReferences(long equateID) throws IOException {
		EquateDB equateDB = getEquateDB(equateID);
		long[] keys = refAdapter.getRecordKeysForEquateID(equateID);
		for (long key : keys) {
			EquateRefDB ref = getEquateRefDB(key);
			removeRef(equateDB, ref);
		}
	}

	private void referenceRemoved(EquateDB equateDB, Address refAddr, short opIndex,
			long dynamichash) {
		program.setChanged(ChangeManager.DOCR_EQUATE_REFERENCE_REMOVED, refAddr, refAddr,
			new EquateInfo(equateDB.getName(), equateDB.getValue(), refAddr, opIndex, dynamichash),
			null);
	}

	//////////////////////////////////////////////////////////////////////
	private class EquateIterator implements Iterator<Equate> {
		private RecordIterator iter;

		private EquateIterator(RecordIterator iter) {
			this.iter = iter;
		}

		/**
		 * @see java.util.Iterator#hasNext()
		 */
		@Override
		public boolean hasNext() {
			if (iter != null) {
				try {
					return iter.hasNext();
				}
				catch (IOException e) {
					program.dbError(e);
				}
			}
			return false;
		}

		/**
		 * @see java.util.Iterator#next()
		 */
		@Override
		public Equate next() {
			if (iter != null) {
				try {
					Record record = iter.next();
					if (record != null) {
						return getEquateDB(record.getKey());
					}
				}
				catch (IOException e) {
					program.dbError(e);
				}
			}
			return null;
		}

		/**
		 * @see java.util.Iterator#remove()
		 */
		@Override
		public void remove() {
			throw new UnsupportedOperationException("remove is not supported.");
		}

	}

	Lock getLock() {
		return lock;
	}

	/**
	 * @see ghidra.program.database.ManagerDB#invalidateCache(boolean)
	 */
	@Override
	public void invalidateCache(boolean all) {
		lock.acquire();
		try {
			refCache.invalidate();
			equateCache.invalidate();
		}
		finally {
			lock.release();
		}
	}

	/**
	 * @see ghidra.program.database.ManagerDB#moveAddressRange(ghidra.program.model.address.Address, ghidra.program.model.address.Address, long, ghidra.util.task.TaskMonitor)
	 */
	@Override
	public void moveAddressRange(Address fromAddr, Address toAddr, long length, TaskMonitor monitor)
			throws CancelledException {
		lock.acquire();
		try {
			invalidateCache(true);
			refAdapter.moveAddressRange(fromAddr, toAddr, length, monitor);
		}
		catch (IOException e) {
			dbError(e);
		}
		finally {
			lock.release();
		}
	}

	/**
	 * Formats a string to the equate format given the enum UUID and the value for the equate. The
	 * formatted strings are used when setting equates from datatypes so that information can be
	 * stored with an equate to point back to that datatype.
	 * @param dtID The enums data type UUID
	 * @param equateValue The value intended for the equate
	 * @return The formatted equate name
	 */
	public static String formatNameForEquate(UniversalID dtID, long equateValue) {
		return DATATYPE_TAG + FORMAT_DELIMITER + dtID.getValue() + FORMAT_DELIMITER + equateValue;
	}

	/**
	 *  Formats a string to the equate error format given the value. Used for rendering formatted
	 *  equates that do not point back to a datatype. 
	 * @param equateValue The value of the equate
	 * @return The error formatted equate name
	 */
	public static String formatNameForEquateError(long equateValue) {
		return "0x" + Long.toString(equateValue, 16) + " " + EquateManager.ERROR_TAG;
	}

	/**
	 * Pulls out the enum data type UUID given a formatted equate name. This UUID should point back
	 * to a datatype.
	 * @param formattedEquateName The formatted equate name to pull the UUID from
	 * @return The enum data type UUID or null if the given name is not formatted.
	 */
	public static UniversalID getDataTypeUUID(String formattedEquateName) {
		if (formattedEquateName.startsWith(DATATYPE_TAG)) {
			return new UniversalID(Long.parseLong(formattedEquateName.split(FORMAT_DELIMITER)[1]));
		}
		return null;
	}

	/**
	 * Pulls out the value of the equate given the formatted equate name. The value stored in the
	 * equate info is a decimal.
	 * @param formattedEquateName The formatted equate name to pull the value from
	 * @return The value of the equate, or -1 if the given name is not formatted.
	 */
	public static long getEquateValueFromFormattedName(String formattedEquateName) {
		if (formattedEquateName.startsWith(DATATYPE_TAG)) {
			return Long.parseLong(formattedEquateName.split(FORMAT_DELIMITER)[2]);
		}
		return -1;
	}
}
