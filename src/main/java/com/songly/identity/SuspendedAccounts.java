package com.songly.identity;

import java.util.Set;
import java.util.UUID;

public interface SuspendedAccounts {

	Set<UUID> suspendedUserIds();
}
