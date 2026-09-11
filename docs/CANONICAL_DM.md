# Canonical DMs

Matrix has no dialable address for a person. A DM is a room, and until an MSC for canonical DMs lands
there is no interoperable way to say "this room is *the* DM with Alice". This is the local
equivalent: a client-side index that lets the rest of the app talk about a DM as a **person**, which
is what Telecom, the contact card and the person shortcuts all need.

> **Scope.** This index makes *our* client agree with itself. It is not interoperable — only an MSC
> gives that. And a custom contact data-kind action appears in the phone's Contacts app, **not** in
> Android Auto, whose dialer surfaces phone numbers and Telecom calls. Auto integration comes from
> [Telecom](ELEMENT_CALL.md#telecom-calltelecomcoordinator), not from here.

## The index

| Direction | Source |
|---|---|
| room → person | `RoomItem.directUserId`, from gomuks' `meta.dm_user_id` |
| person → room | `RoomListUiCoordinator.getDirectRoomIdForUser()` — most recently active candidate |

`CanonicalDmCoordinator` is the entry point for both. Room → person resolves in order: the in-memory
room, the persisted row (`RoomMetadataStore`), `m.direct`, then the room's own membership
(`AccountDataCoordinator.inferSingleOtherMemberMxid`). Person → room delegates to the existing
resolver rather than inventing a second selection rule — **that recency tie-break *is* the
canonicalisation rule**.

### Why it must be persisted and sticky

`meta` is sent on the initial sync and thereafter **only when a room's metadata actually changed**;
when nothing changed it is omitted from the room object entirely. So for most syncs, for most rooms,
`dm_user_id` is simply not in the payload.

Two consequences, both load-bearing:

- **Every merge site uses `?:`-preserve, never OR** — `directUserId = incoming ?: existing`. A null
  means "this delta said nothing", never "there is no partner". The merge sites are the same ones
  that already preserve `isDirectMessage` (`SyncRoomsCoordinator`, `SyncBatchProcessor`,
  `AppViewModel`), plus `RoomMetadataStore.mergeIntoMirror` at the storage layer.
- **`RoomListCache.metaUpdateFor`'s early-return guard includes it.** Without that, a DM whose
  display name is still the raw room id and whose sort-ts is 0 would never persist its mxid — exactly
  the rooms the index can least resolve by other means.

Schema: `room_metadata.dm_user_id TEXT`, added in **v6 → v7**. Purely additive; existing rows stay
NULL and fill in from the next initial sync, so there is nothing to backfill.

`SpaceRoomParser.detectDirectMessage` returns a `DmDetection(isDirect, dmUserId)`. Only
`meta.dm_user_id` yields an identity: the `m.direct` branch leaves it null (filled in by
`updateRoomsDirectMessageStatus`, where `m.direct` is authoritative), and the "room name looks like
an mxid" fallback deliberately yields null too — asserting identity from a display string is how you
end up calling the wrong person.

## Calls address the person

`CallTelecomCoordinator` uses `matrix:u/<user>` when the canonical DM index knows the partner, and
the room id otherwise. That string is byte-identical to `PersonsApi.buildPersonUri()` and to the
`DATA1` our contact rows carry, so one identity spans shortcuts, contacts and calls. The call's
`displayName` prefers the partner's own display name over the room's, which for a DM is often a raw
mxid or a bridge-generated string.

This is identity, not contact matching: the Jetpack `PhoneAccount` declares no supported URI schemes,
so call UIs still render `displayName`, not a looked-up contact.

## The contact card

`ContactsSyncService` writes three custom-MIME Data rows per Matrix contact, all sharing the same
`matrix:u/<user>` in `DATA1` and differing only in type and action label:

| MIME type | Label | Tap |
|---|---|---|
| `…matrix.user` | Send Matrix message | Profile sheet (as before) |
| `…matrix.call` | Matrix call | Voice call in the canonical DM |
| `…matrix.videocall` | Matrix video call | Video call in the canonical DM |

Each is declared as a `ContactsDataKind` in `res/xml/contacts.xml` and as a `MainActivity`
intent-filter. `ensureMatrixDataRows` adds missing rows on update, so contacts saved before the call
actions existed gain them without being re-added.

**Cold start is the normal case here.** A contact tap routinely starts the process, so there is no
ViewModel to resolve with and no synced room list to resolve from. The tap parks a
`CallAction.CallUser(mxid, intent)` in `PendingCallAction` — the same hand-off the incoming-call ring
uses — and the drain beside `CallOverlay` holds it until rooms exist rather than consuming it and
reporting "no DM" merely because sync had not finished. With no DM room at all, it falls back to the
profile sheet (which offers to start the chat) with a Toast; it never creates a room from a tap.

## Linking to a phone contact

So that *Alice +351912345678* and *Alice @alice:matrix.org* are one person, `ContactLinkCoordinator`
writes a `ContactsContract.AggregationExceptions` row of `TYPE_KEEP_TOGETHER` between **our**
RawContact and the user-picked one.

Not `mergeWithExistingContact`, which puts our rows on a foreign RawContact, because:

1. **They would not render.** The Contacts app resolves a custom MIME type through the
   `ContactsAccountType` XML of the row's *owning* account. Ours is registered for
   `net.vrkknn.andromuks.matrix`, so a Matrix row on a Google RawContact has no icon or labels.
2. **Their sync adapter owns those rows** and reconciles them against its own server; `removeContact`
   only deletes RawContacts under our account, so they leak.
3. **It cannot be undone** without hunting rows on a contact we do not own.

Linking is always an explicit user choice via the system contact picker —
`findExistingContactByPhoneOrEmail` needs a phone or email and Matrix cannot tell us another user's,
so there is nothing to match on automatically. The picker's contact is resolved to a RawContact
belonging to any account but ours, preferring one that carries a phone number.

The link is cached in `RawContacts.SYNC2` on our own RawContact as a **lookup key** (raw-contact ids
do not survive a Contacts restore). The aggregation exception is the source of truth; SYNC2 is only
the button's label, and it is deleted with our contact so it can never outlive the link. Unlinking
writes `TYPE_KEEP_SEPARATE` — not `TYPE_AUTOMATIC`, which would let the provider re-aggregate them.

## Known limits

- A Contacts restore assigns new raw-contact ids and drops the exception; the link shows as absent
  and must be re-made.
- OEM Contacts apps (Samsung, Xiaomi) sometimes collapse or drop unknown custom data kinds, in which
  case the call rows may not appear — the profile sheet's own buttons remain the route.
- `accountType` and the MIME types are not flavour-suffixed while `applicationId` and
  `contactsAuthority` are, so side-by-side flavours share an account type. Pre-existing.
