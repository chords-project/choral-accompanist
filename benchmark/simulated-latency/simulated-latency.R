library(dplyr)
library(ggplot2)

MAX_LATENCY <- 5
WIDTH <- 6
HEIGHT <- 4

orchestrator <- read.csv("orchestrator.csv", sep = ";")
choreography <- read.csv("choreography.csv", sep = ";")
asymmetric_choreography <- read.csv("asymmetric-choreography.csv", sep = ";")

equidistant_bar_plot <- function(chain_len) {
  o <- orchestrator %>%
    filter(simulated_latency <= MAX_LATENCY) %>%
    filter(chain_length == chain_len) %>%
    group_by(simulated_latency) %>%
    summarize(
      type = "O",
      subtype = "Network",
      time = mean(end_to_end_time) / 1000000,
    )

  c1 <- choreography %>%
    filter(simulated_latency <= MAX_LATENCY) %>%
    filter(chain_length == chain_len) %>%
    group_by(simulated_latency) %>%
    summarize(
      type = "A",
      subtype = "Sidecar",
      time = mean(sidecar) / 1000000,
    )

  c2 <- choreography %>%
    filter(simulated_latency <= MAX_LATENCY) %>%
    filter(chain_length == chain_len) %>%
    group_by(simulated_latency) %>%
    summarize(
      type = "A",
      subtype = "Network",
      time = (mean(total) - mean(sidecar)) / 1000000,
    )

  data <- bind_rows(o, c1, c2)

  milliseconds <- function(x) {
    paste(x, "ms")
  }

  ggplot(data, aes(x = type, y = time, fill = subtype)) +
    geom_bar(position = "stack", stat = "identity") +
    scale_fill_manual(values = c("#F15350", "#38B84D")) +
    facet_grid(~simulated_latency, labeller = as_labeller(milliseconds)) +
    theme(
      legend.position = "left",
      text = element_text(size = 20),
      strip.background = element_rect(fill = NA, color = "white"),
      plot.title = element_text(hjust = 0.5),
      plot.subtitle = element_text(hjust = 0.5),
    ) +
    labs(
      subtitle = "Network latency (ms)",
      #subtitle = sprintf("Chain of %d services", chain_len),
      fill = NULL,
      x = NULL, # "Network latency",
      y = "End-to-end latency (ms)",
    ) +
    scale_y_continuous(
      # labels = milliseconds,
      # breaks = scales::pretty_breaks(n = 20)
    )
}

asymmetric_bar_plot <- function(latency) {
  o <- orchestrator %>%
    filter(simulated_latency == latency) %>%
    group_by(chain_length) %>%
    summarize(
      type = "O",
      subtype = "Network",
      time = mean(end_to_end_time) / 1000000,
    )
  
  c1 <- asymmetric_choreography %>%
    filter(simulated_latency == latency) %>%
    group_by(chain_length) %>%
    summarize(
      type = "A",
      subtype = "Sidecar",
      time = mean(sidecar) / 1000000,
    )
  
  c2 <- asymmetric_choreography %>%
    filter(simulated_latency == latency) %>%
    group_by(chain_length) %>%
    summarize(
      type = "A",
      subtype = "Network",
      time = (mean(total) - mean(sidecar)) / 1000000,
    )
  
  data <- bind_rows(o, c1, c2)
  
  ggplot(data, aes(x = type, y = time, fill = subtype)) +
    geom_bar(position = "stack", stat = "identity") +
    scale_fill_manual(values = c("#F15350", "#38B84D")) +
    facet_grid(~chain_length) +
    theme(
      legend.position = "none",
      text = element_text(size = 20),
      strip.background = element_rect(fill = NA, color = "white"),
      plot.title = element_text(hjust = 0.5),
      plot.subtitle = element_text(hjust = 0.5),
    ) +
    labs(
      subtitle = "Number of workers",
      #subtitle = sprintf("Chain of %d services", chain_len),
      fill = NULL,
      x = NULL,
      y = "End-to-end latency (ms)",
    ) +
    scale_y_continuous(
      # breaks = scales::pretty_breaks(n = 20)
    )
}

p <- equidistant_bar_plot(2)
ggsave("equidistant_latency_2_workers.png", p, width = WIDTH, height = HEIGHT)
tikzDevice::tikz("equidistant_latency_2_workers.tex", width = WIDTH, height = HEIGHT)
print(p)
dev.off()

p <- asymmetric_bar_plot(1)
ggsave("asymmetric_latency_1_ms.png", p, width = WIDTH - 1, height = HEIGHT)
tikzDevice::tikz("asymmetric_latency_1_ms.tex", width = WIDTH - 1, height = HEIGHT)
print(p)
dev.off()