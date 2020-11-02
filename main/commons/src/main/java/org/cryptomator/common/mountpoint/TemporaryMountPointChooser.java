package org.cryptomator.common.mountpoint;

import org.apache.commons.lang3.SystemUtils;
import org.cryptomator.common.Environment;
import org.cryptomator.common.settings.VaultSettings;
import org.cryptomator.common.settings.VolumeImpl;
import org.cryptomator.common.vaults.MountPointRequirement;
import org.cryptomator.common.vaults.Volume;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

public class TemporaryMountPointChooser implements MountPointChooser {

	public static final int PRIORITY = 300;

	private static final Logger LOG = LoggerFactory.getLogger(TemporaryMountPointChooser.class);
	private static final int MAX_TMPMOUNTPOINT_CREATION_RETRIES = 10;

	private final VaultSettings vaultSettings;
	private final Environment environment;

	@Inject
	public TemporaryMountPointChooser(VaultSettings vaultSettings, Environment environment) {
		this.vaultSettings = vaultSettings;
		this.environment = environment;
	}

	@Override
	public boolean isApplicable(Volume caller) {
		if (this.environment.getMountPointsDir().isEmpty()) {
			LOG.warn("\"cryptomator.mountPointsDir\" is not set to a valid path!");
			return false;
		}
		return true;
	}

	@Override
	public Optional<Path> chooseMountPoint(Volume caller) {
		return this.environment.getMountPointsDir().map(p -> choose(p, caller));
	}

	private Path choose(Path parent, Volume caller) {
		String basename = this.vaultSettings.mountName().get(); //TODO: this is a normalized name, but if we mount into a folder we do not need to normalize
		for (int i = 0; i < MAX_TMPMOUNTPOINT_CREATION_RETRIES; i++) {
			Path mountPoint = parent.resolve(basename + "_" + i);
			if (Files.notExists(mountPoint, LinkOption.NOFOLLOW_LINKS)) { //let's be explicit
				return mountPoint;
			} else {
				try {
					removeLeftOvers(mountPoint, caller);
					return mountPoint;
				} catch (IOException e) {
					//NO-OP, try next
				}
			}
		}
		LOG.error("Failed to find feasible mountpoint at {}{}{}_x. Giving up after {} attempts.", parent, File.separator, basename, MAX_TMPMOUNTPOINT_CREATION_RETRIES);
		return null;
	}

	//see https://github.com/cryptomator/cryptomator/issues/1013 and https://github.com/cryptomator/cryptomator/issues/1061
	private void removeLeftOvers(Path mountPoint, Volume caller) throws IOException {
		if (!Files.isDirectory(mountPoint, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException(); //if not a directory, we do not touch it
		}

		if (VolumeImpl.DOKANY.equals(caller.getImplementationType())) {
			try {
				var attrTarget = Files.readAttributes(mountPoint, BasicFileAttributes.class); //we follow the link and see if it exists
			} catch (IOException e) {
				Files.delete(mountPoint); //broken link file, we delete it
				return;
			}
		} else if (VolumeImpl.FUSE.equals(caller.getImplementationType())) {
			try (DirectoryStream<Path> ds = Files.newDirectoryStream(mountPoint)) {
				if (!ds.iterator().hasNext()) {
					if (caller.getMountPointRequirement().equals(MountPointRequirement.PARENT_NO_MOUNT_POINT)) {
						Files.delete(mountPoint);
					}
					return;
				}
			}
		}

		throw new IOException(); //in the default we do not touch anything
	}

	@Override
	public boolean prepare(Volume caller, Path mountPoint) throws InvalidMountPointException {
		// https://github.com/osxfuse/osxfuse/issues/306#issuecomment-245114592:
		// In order to allow non-admin users to mount FUSE volumes in `/Volumes`,
		// starting with version 3.5.0, FUSE will create non-existent mount points automatically.
		if (SystemUtils.IS_OS_MAC && mountPoint.getParent().equals(Paths.get("/Volumes"))) {
			return false;
		}

		try {
			switch (caller.getMountPointRequirement()) {
				case PARENT_NO_MOUNT_POINT -> {
					Files.createDirectories(mountPoint.getParent());
					LOG.debug("Successfully created folder for mount point: {}", mountPoint);
					return false;
				}
				case EMPTY_MOUNT_POINT -> {
					Files.createDirectories(mountPoint);
					LOG.debug("Successfully created mount point: {}", mountPoint);
					return true;
				}
				case NONE -> {
					//Requirement "NONE" doesn't make any sense here.
					//No need to prepare/verify a Mountpoint without requiring one...
					throw new InvalidMountPointException(new IllegalStateException("Illegal MountPointRequirement"));
				}
				default -> {
					//Currently the case for "PARENT_OPT_MOUNT_POINT"
					throw new InvalidMountPointException(new IllegalStateException("Not implemented"));
				}
			}
		} catch (IOException exception) {
			throw new InvalidMountPointException("IOException while preparing mountpoint", exception);
		}
	}

	@Override
	public void cleanup(Volume caller, Path mountPoint) {
		try {
			Files.delete(mountPoint);
			LOG.debug("Successfully deleted mount point: {}", mountPoint);
		} catch (IOException e) {
			LOG.warn("Could not delete mount point: {}", e.getMessage());
		}
	}

	@Override
	public int getPriority() {
		return PRIORITY;
	}
}