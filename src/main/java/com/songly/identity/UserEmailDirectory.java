package com.songly.identity;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface UserEmailDirectory {

	Map<UUID, String> emailsOf(Collection<UUID> userIds);
}
