package org.xpfarm.ollama.companion;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * The conversation UI. A Paper Dialog with one text input; Geyser auto-converts it to a Bedrock
 * form, so this single path serves both editions with no Geyser dependency. max_length is set
 * explicitly because Geyser defaults dialog inputs to 32 chars and ignores multiline.
 */
public final class CompanionDialog {

    private final CompanionConversation conversation;

    public CompanionDialog(CompanionConversation conversation) {
        this.conversation = conversation;
    }

    public void open(Player player) {
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Talk to your llama"))
                        .body(List.of(DialogBody.plainMessage(Component.text("What do you want to ask?"))))
                        .inputs(List.of(DialogInput.text("prompt", Component.text("You:"))
                                .maxLength(256)   // Bedrock chat is dropped past 256 anyway; match it.
                                .build()))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Component.text("Ask"))
                                .action(DialogAction.customClick((response, audience) -> {
                                    String text = response.getText("prompt");
                                    if (audience instanceof Player p && text != null && !text.isBlank()) {
                                        conversation.ask(p, text.strip());
                                    }
                                }, null))
                                .build(),
                        ActionButton.builder(Component.text("Never mind")).build())));
        player.showDialog(dialog);
    }
}
