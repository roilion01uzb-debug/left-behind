package dev.leftbehind.story;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundClearDialogPacket;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerPlayer;

/** Conversation and journal windows, built on the game's own dialog screens. */
public final class Dialogs {
    /** A button. A null command just closes the window. */
    public record Choice(String label, String command) {}

    private Dialogs() {}

    public static void show(ServerPlayer player, String title, List<Component> body, List<Choice> choices, Choice exit, int columns) {
        List<DialogBody> messages = new ArrayList<>();
        for (Component line : body) messages.add(new PlainMessage(line, 300));
        CommonDialogData common = new CommonDialogData(Component.literal(title), Optional.empty(), true, false,
                DialogAction.CLOSE, messages, List.of());
        List<ActionButton> actions = new ArrayList<>();
        for (Choice choice : choices) actions.add(button(choice, columns == 1 ? 260 : 150));
        if (actions.isEmpty()) actions.add(button(exit, 200));
        Dialog dialog = new MultiActionDialog(common, actions, Optional.of(button(exit, 200)), columns);
        player.openDialog(Holder.direct(dialog));
    }

    private static ActionButton button(Choice choice, int width) {
        return new ActionButton(new CommonButtonData(Component.literal(choice.label()), width),
                choice.command() == null ? Optional.empty() : Optional.of(new StaticAction(new ClickEvent.RunCommand(choice.command()))));
    }

    public static void close(ServerPlayer player) {
        player.connection.send(ClientboundClearDialogPacket.INSTANCE);
    }
}
